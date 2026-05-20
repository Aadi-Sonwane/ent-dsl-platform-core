package com.enterprise.platform.maven

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class AssemblyPackagingManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String ASSEMBLY_BIN_SUFFIX = "-bin"
    private static final String ASSEMBLY_SRC_SUFFIX = "-src"
    private static final List<String> VALID_FORMATS = ["tar.gz", "tar.bz2", "zip", "dir"]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    AssemblyPackagingManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    AssemblyPackagingManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    String generateAssemblyDescriptor(String name, Map config) {
        LoggingUtils.info("AssemblyPackagingManager",
            "Generating assembly descriptor '${name}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(name)) {
            String errMsg = "Assembly descriptor name must not be null or empty"
            audit.emitAuditEvent("ASSEMBLY_DESCRIPTOR_FAILED", errMsg, correlationId)
            throw new IllegalArgumentException(errMsg)
        }

        try {
            String descriptorId = name
            String baseDir = resolveBaseDirectory(name, config)
            List<String> formats = resolveFormats(config)
            Map includeSpec = resolveIncludeSpec(config)
            Map excludeSpec = resolveExcludeSpec(config)
            Map fileSets = resolveFileSets(config)
            List<Map> dependencySets = resolveDependencySets(config)
            List<Map> moduleSets = resolveModuleSets(config)

            String xml = buildAssemblyXml(
                descriptorId, baseDir, formats,
                includeSpec, excludeSpec,
                fileSets, dependencySets, moduleSets)

            LoggingUtils.info("AssemblyPackagingManager",
                "Assembly descriptor '${name}' generated (${xml.length()} chars) [correlationId=${correlationId}]")

            audit.emitAuditEvent("ASSEMBLY_DESCRIPTOR_CREATED",
                "Assembly descriptor '${name}' generated", correlationId)

            return xml

        } catch (Exception e) {
            String errMsg = "Failed to generate assembly descriptor '${name}': ${e.message}"
            LoggingUtils.error("AssemblyPackagingManager", errMsg, e)
            audit.emitAuditEvent("ASSEMBLY_DESCRIPTOR_FAILED", errMsg, correlationId)
            throw new RuntimeException(errMsg, e)
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String resolveBaseDirectory(String name, Map config) {
        if (config != null) {
            String baseDir = extractStringField(config, "baseDirectory")
            if (ValidationUtils.isNonEmpty(baseDir)) return baseDir
        }
        return name
    }

    @NonCPS
    private List<String> resolveFormats(Map config) {
        List<String> formats = []
        if (config != null) {
            Object formatsRaw = config.get("formats")
            if (formatsRaw instanceof List) {
                for (Object f : (List) formatsRaw) {
                    if (f instanceof String && VALID_FORMATS.contains(f.toString())) {
                        formats.add(f.toString())
                    }
                }
            }
            String singleFormat = extractStringField(config, "format")
            if (ValidationUtils.isNonEmpty(singleFormat) && VALID_FORMATS.contains(singleFormat)) {
                formats.add(singleFormat)
            }
        }
        if (formats.isEmpty()) {
            formats.add("tar.gz")
        }
        return formats
    }

    @NonCPS
    private Map resolveIncludeSpec(Map config) {
        Map spec = [:]
        if (config == null) return spec
        Object includesRaw = config.get("includes")
        if (includesRaw instanceof List) {
            spec.includes = includesRaw
        }
        Object includeBaseRaw = config.get("includeBaseDirectory")
        if (includeBaseRaw instanceof Boolean) {
            spec.includeBaseDirectory = includeBaseRaw
        }
        return spec
    }

    @NonCPS
    private Map resolveExcludeSpec(Map config) {
        Map spec = [:]
        if (config == null) return spec
        Object excludesRaw = config.get("excludes")
        if (excludesRaw instanceof List) {
            spec.excludes = excludesRaw
        }
        return spec
    }

    @NonCPS
    private Map resolveFileSets(Map config) {
        Map fileSetConfig = [:]
        if (config == null) return fileSetConfig
        Object fileSetsRaw = config.get("fileSets")
        if (fileSetsRaw instanceof List) {
            fileSetConfig.fileSets = (List) fileSetsRaw
        }
        return fileSetConfig
    }

    @NonCPS
    private List<Map> resolveDependencySets(Map config) {
        List<Map> sets = []
        if (config == null) return sets
        Object depSetsRaw = config.get("dependencySets")
        if (depSetsRaw instanceof List) {
            for (Object item : (List) depSetsRaw) {
                if (item instanceof Map) sets.add((Map) item)
            }
        }
        if (sets.isEmpty()) {
            Map defaultSet = [:]
            defaultSet["useProjectArtifact"] = true
            defaultSet["outputDirectory"] = "lib"
            defaultSet["unpack"] = false
            defaultSet["scope"] = "runtime"
            sets.add(defaultSet)
        }
        return sets
    }

    @NonCPS
    private List<Map> resolveModuleSets(Map config) {
        List<Map> sets = []
        if (config == null) return sets
        Object modSetsRaw = config.get("moduleSets")
        if (modSetsRaw instanceof List) {
            for (Object item : (List) modSetsRaw) {
                if (item instanceof Map) sets.add((Map) item)
            }
        }
        return sets
    }

    @NonCPS
    private String buildAssemblyXml(
            String id, String baseDir, List<String> formats,
            Map includeSpec, Map excludeSpec,
            Map fileSets, List<Map> dependencySets, List<Map> moduleSets) {

        StringBuilder xml = new StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<assembly xmlns=\"http://maven.apache.org/ASSEMBLY/4.0.0\"\n")
        xml.append("          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        xml.append("          xsi:schemaLocation=\"http://maven.apache.org/ASSEMBLY/4.0.0 ")
        xml.append("http://maven.apache.org/xsd/assembly-4.0.0.xsd\">\n")
        xml.append("  <id>").append(escapeXml(id)).append("</id>\n")
        xml.append("  <baseDirectory>").append(escapeXml(baseDir)).append("</baseDirectory>\n")

        xml.append("  <formats>\n")
        for (String format : formats) {
            xml.append("    <format>").append(escapeXml(format)).append("</format>\n")
        }
        xml.append("  </formats>\n")

        Object includeBaseRaw = includeSpec.get("includeBaseDirectory")
        Boolean includeBaseDir = includeBaseRaw instanceof Boolean ? (Boolean) includeBaseRaw : null
        if (includeBaseDir != null) {
            xml.append("  <includeBaseDirectory>").append(includeBaseDir.toString()).append("</includeBaseDirectory>\n")
        }

        List includes = includeSpec.get("includes") instanceof List ? (List) includeSpec.get("includes") : []
        List excludes = excludeSpec.get("excludes") instanceof List ? (List) excludeSpec.get("excludes") : []

        if (!includes.isEmpty()) {
            xml.append("  <includeFilters>\n")
            for (Object inc : includes) {
                xml.append("    <include>").append(escapeXml(inc.toString())).append("</include>\n")
            }
            xml.append("  </includeFilters>\n")
        }
        if (!excludes.isEmpty()) {
            xml.append("  <excludeFilters>\n")
            for (Object exc : excludes) {
                xml.append("    <exclude>").append(escapeXml(exc.toString())).append("</exclude>\n")
            }
            xml.append("  </excludeFilters>\n")
        }

        List<Map> fsList = fileSets.get("fileSets") instanceof List ? (List<Map>) fileSets.get("fileSets") : []
        if (!fsList.isEmpty()) {
            xml.append("  <fileSets>\n")
            for (Map fs : fsList) {
                xml.append("    <fileSet>\n")
                String fsDir = extractStringField(fs, "directory")
                if (ValidationUtils.isNonEmpty(fsDir)) {
                    xml.append("      <directory>").append(escapeXml(fsDir)).append("</directory>\n")
                }
                String fsOutputDir = extractStringField(fs, "outputDirectory")
                if (ValidationUtils.isNonEmpty(fsOutputDir)) {
                    xml.append("      <outputDirectory>").append(escapeXml(fsOutputDir)).append("</outputDirectory>\n")
                }
                Object fsIncludes = fs.get("includes")
                if (fsIncludes instanceof List && !((List) fsIncludes).isEmpty()) {
                    xml.append("      <includes>\n")
                    for (Object inc : (List) fsIncludes) {
                        xml.append("        <include>").append(escapeXml(inc.toString())).append("</include>\n")
                    }
                    xml.append("      </includes>\n")
                }
                Object fsExcludes = fs.get("excludes")
                if (fsExcludes instanceof List && !((List) fsExcludes).isEmpty()) {
                    xml.append("      <excludes>\n")
                    for (Object exc : (List) fsExcludes) {
                        xml.append("        <exclude>").append(escapeXml(exc.toString())).append("</exclude>\n")
                    }
                    xml.append("      </excludes>\n")
                }
                Object filteredRaw = fs.get("filtered")
                if (filteredRaw instanceof Boolean) {
                    xml.append("      <filtered>").append(filteredRaw.toString()).append("</filtered>\n")
                }
                xml.append("    </fileSet>\n")
            }
            xml.append("  </fileSets>\n")
        }

        if (!dependencySets.isEmpty()) {
            xml.append("  <dependencySets>\n")
            for (Map ds : dependencySets) {
                xml.append("    <dependencySet>\n")
                Object useProjectArtifact = ds.get("useProjectArtifact")
                if (useProjectArtifact instanceof Boolean) {
                    xml.append("      <useProjectArtifact>").append(useProjectArtifact.toString()).append("</useProjectArtifact>\n")
                }
                String outputDir = extractStringField(ds, "outputDirectory")
                if (ValidationUtils.isNonEmpty(outputDir)) {
                    xml.append("      <outputDirectory>").append(escapeXml(outputDir)).append("</outputDirectory>\n")
                }
                Object unpackRaw = ds.get("unpack")
                if (unpackRaw instanceof Boolean) {
                    xml.append("      <unpack>").append(unpackRaw.toString()).append("</unpack>\n")
                }
                String scope = extractStringField(ds, "scope")
                if (ValidationUtils.isNonEmpty(scope)) {
                    xml.append("      <scope>").append(escapeXml(scope)).append("</scope>\n")
                }
                Object dsIncludes = ds.get("includes")
                if (dsIncludes instanceof List && !((List) dsIncludes).isEmpty()) {
                    xml.append("      <includes>\n")
                    for (Object inc : (List) dsIncludes) {
                        xml.append("        <include>").append(escapeXml(inc.toString())).append("</include>\n")
                    }
                    xml.append("      </includes>\n")
                }
                Object dsExcludes = ds.get("excludes")
                if (dsExcludes instanceof List && !((List) dsExcludes).isEmpty()) {
                    xml.append("      <excludes>\n")
                    for (Object exc : (List) dsExcludes) {
                        xml.append("        <exclude>").append(escapeXml(exc.toString())).append("</exclude>\n")
                    }
                    xml.append("      </excludes>\n")
                }
                xml.append("    </dependencySet>\n")
            }
            xml.append("  </dependencySets>\n")
        }

        if (!moduleSets.isEmpty()) {
            xml.append("  <moduleSets>\n")
            for (Map ms : moduleSets) {
                xml.append("    <moduleSet>\n")
                Object msIncludes = ms.get("includes")
                if (msIncludes instanceof List && !((List) msIncludes).isEmpty()) {
                    xml.append("      <includes>\n")
                    for (Object inc : (List) msIncludes) {
                        xml.append("        <include>").append(escapeXml(inc.toString())).append("</include>\n")
                    }
                    xml.append("      </includes>\n")
                }
                Map binaries = extractMapField(ms, "binaries")
                if (binaries != null) {
                    xml.append("      <binaries>\n")
                    Object attOutputDir = binaries.get("outputDirectory")
                    if (attOutputDir instanceof String && ValidationUtils.isNonEmpty((String) attOutputDir)) {
                        xml.append("        <outputDirectory>").append(escapeXml((String) attOutputDir)).append("</outputDirectory>\n")
                    }
                    Object attUnpack = binaries.get("unpack")
                    if (attUnpack instanceof Boolean) {
                        xml.append("        <unpack>").append(attUnpack.toString()).append("</unpack>\n")
                    }
                    xml.append("        <dependencySets>\n")
                    xml.append("          <dependencySet>\n")
                    xml.append("            <useProjectArtifact>true</useProjectArtifact>\n")
                    xml.append("          </dependencySet>\n")
                    xml.append("        </dependencySets>\n")
                    xml.append("      </binaries>\n")
                }
                xml.append("    </moduleSet>\n")
            }
            xml.append("  </moduleSets>\n")
        }

        xml.append("</assembly>\n")
        return xml.toString()
    }

    @NonCPS
    private String escapeXml(String input) {
        if (input == null) return ""
        return input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    @NonCPS
    private String extractStringField(Map map, String key) {
        if (map == null) return null
        Object value = map.get(key)
        if (value instanceof String) return (String) value
        if (value != null) return value.toString()
        return null
    }

    @NonCPS
    private Map extractMapField(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof Map) return (Map) value
        return null
    }
}
