package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class SBOMGenerationManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> VALID_FORMATS = ["cyclonedx", "spdx", "swid"]
    private static final List<String> VALID_OUTPUT_FORMATS = ["json", "xml"]
    private static final String DEFAULT_SBOM_VERSION = "1.5"

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId

    SBOMGenerationManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    SBOMGenerationManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map generateSbom(String projectPath, Map options) {
        LoggingUtils.info("SBOMGenerationManager",
            "Generating SBOM for project '${projectPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(projectPath)) {
            throw new IllegalArgumentException("Project path must not be null or empty")
        }
        if (options == null) options = [:]

        long startTime = System.currentTimeMillis()
        String sbomFile = ".sbom-${correlationId}.json"
        String status = "FAILED"
        String generator = "unknown"
        List<Map> components = []
        int totalComponents = 0

        try {
            String format = resolveFormat(options)
            String outputFormat = resolveOutputFormat(options)
            String sbomVersion = options.sbomVersion instanceof String ?
                options.sbomVersion.toString() : DEFAULT_SBOM_VERSION
            sbomFile = ".sbom-${correlationId}.${outputFormat}"
            generator = options.generator instanceof String ?
                options.generator.toString().toLowerCase() : "cyclonedx-maven"

            switch (generator) {
                case "cyclonedx-maven":
                    status = generateCycloneDxMaven(projectPath, sbomFile, options)
                    break
                case "cyclonedx-cli":
                    status = generateCycloneDxCli(projectPath, sbomFile, format, outputFormat, options)
                    break
                case "spdx-maven":
                    status = generateSpdxMaven(projectPath, sbomFile, options)
                    break
                default:
                    status = generateCycloneDxMaven(projectPath, sbomFile, options)
                    break
            }

            String sbomContent = readSbomFile(sbomFile)
            if (ValidationUtils.isNonEmpty(sbomContent)) {
                Map parsed = parseSbomContent(sbomContent, format)
                components = parsed.components ?: []
                totalComponents = parsed.totalComponents ?: 0
            }

            long duration = System.currentTimeMillis() - startTime

            LoggingUtils.info("SBOMGenerationManager",
                "SBOM generated in ${duration}ms: ${totalComponents} components (${format}, ${generator}) [correlationId=${correlationId}]")

            audit.emitAuditEvent("SBOM_GENERATED",
                "SBOM generated: ${totalComponents} components, format=${format}, generator=${generator}", correlationId)
            telemetry.emitEvent("security.sbom", "generated", [
                correlationId: correlationId,
                durationMs: duration,
                totalComponents: totalComponents,
                format: format,
                generator: generator,
                projectPath: projectPath,
                status: status
            ])

            return [
                status: status,
                correlationId: correlationId,
                durationMs: duration,
                format: format,
                generator: generator,
                outputFile: sbomFile,
                totalComponents: totalComponents,
                components: components,
                sbomContent: sbomContent
            ]

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "SBOM generation failed: ${e.message}"
            LoggingUtils.error("SBOMGenerationManager", errMsg, e)
            audit.emitAuditEvent("SBOM_GENERATION_FAILED", errMsg, correlationId)
            telemetry.emitEvent("security.sbom", "failed", [
                correlationId: correlationId,
                durationMs: duration,
                error: e.message
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Generators
     */

    private String generateCycloneDxMaven(String projectPath, String sbomFile, Map options) {
        LoggingUtils.info("SBOMGenerationManager",
            "Generating CycloneDX SBOM via Maven plugin [correlationId=${correlationId}]")

        String pomPath = options.pomPath instanceof String ?
            options.pomPath.toString() : "${projectPath}/pom.xml"
        String settingsPath = options.settingsPath instanceof String ?
            options.settingsPath.toString() : ""

        List<String> cmdArgs = ["mvn", "-f", pomPath,
                                "org.cyclonedx:cyclonedx-maven-plugin:2.8.0:makeAggregateBom"]
        cmdArgs.add("-Dcyclonedx.skip=false")
        cmdArgs.add("-Dcyclonedx.includeBomSerialNumber=true")
        cmdArgs.add("-Dcyclonedx.includeCompileScope=true")
        cmdArgs.add("-Dcyclonedx.includeProvidedScope=true")
        cmdArgs.add("-Dcyclonedx.includeRuntimeScope=true")
        cmdArgs.add("-Dcyclonedx.includeSystemScope=true")
        cmdArgs.add("-Dcyclonedx.includeTestScope=false")
        cmdArgs.add("-Dcyclonedx.outputFormat=json")
        cmdArgs.add("-Dcyclonedx.outputName=${sbomFile.replaceAll('\\.json$', '')}")
        cmdArgs.add("-Dcyclonedx.outputDirectory=${steps.env.WORKSPACE ?: '.'}")
        cmdArgs.add("-q")

        if (ValidationUtils.isNonEmpty(settingsPath)) {
            cmdArgs.add("-s")
            cmdArgs.add(settingsPath)
        }

        if (options.mavenProfiles instanceof List) {
            for (Object profile : (List) options.mavenProfiles) {
                cmdArgs.add("-P${profile.toString()}")
            }
        }

        String cmd = cmdArgs.join(" ")

        Map execResult = shellUtils.execute(cmd, [
            timeoutMs: resolveTimeout(options),
            captureOutput: true,
            validExitCodes: [0]
        ])

        String generatedFile = "${steps.env.WORKSPACE ?: '.'}/target/${sbomFile.replaceAll('\\.json$', '')}.json"
        if (steps.fileExists(generatedFile)) {
            steps.sh(script: "cp '${generatedFile}' '${steps.env.WORKSPACE ?: '.'}/${sbomFile}'", returnStatus: true)
        }

        return "COMPLETED"
    }

    private String generateCycloneDxCli(String projectPath, String sbomFile, String format,
                                        String outputFormat, Map options) {
        LoggingUtils.info("SBOMGenerationManager",
            "Generating CycloneDX SBOM via CLI [correlationId=${correlationId}]")

        String cliBinary = options.cliBinaryPath instanceof String ?
            options.cliBinaryPath.toString() : "cyclonedx-cli"

        List<String> cmdArgs = [cliBinary, "convert",
                                "--input-format", "maven",
                                "--output-format", outputFormat,
                                "--output-file", sbomFile,
                                projectPath]

        if (options.includeLicenses instanceof Boolean && (Boolean) options.includeLicenses) {
            cmdArgs.add("--include-licenses")
        }

        String cmd = cmdArgs.join(" ")

        shellUtils.execute(cmd, [
            timeoutMs: resolveTimeout(options),
            captureOutput: true,
            validExitCodes: [0]
        ])

        return "COMPLETED"
    }

    private String generateSpdxMaven(String projectPath, String sbomFile, Map options) {
        LoggingUtils.info("SBOMGenerationManager",
            "Generating SPDX SBOM via Maven plugin [correlationId=${correlationId}]")

        String pomPath = options.pomPath instanceof String ?
            options.pomPath.toString() : "${projectPath}/pom.xml"

        List<String> cmdArgs = ["mvn", "-f", pomPath,
                                "org.spdx:spdx-maven-plugin:0.7.0:createSPDX"]
        cmdArgs.add("-Dspdx.skip=false")
        cmdArgs.add("-Dspdx.outputFormat=JSON")
        cmdArgs.add("-q")

        if (options.mavenProfiles instanceof List) {
            for (Object profile : (List) options.mavenProfiles) {
                cmdArgs.add("-P${profile.toString()}")
            }
        }

        String cmd = cmdArgs.join(" ")

        shellUtils.execute(cmd, [
            timeoutMs: resolveTimeout(options),
            captureOutput: true,
            validExitCodes: [0]
        ])

        String generatedFile = "${steps.env.WORKSPACE ?: '.'}/target/spdx.json"
        if (steps.fileExists(generatedFile)) {
            steps.sh(script: "cp '${generatedFile}' '${steps.env.WORKSPACE ?: '.'}/${sbomFile}'", returnStatus: true)
        }

        return "COMPLETED"
    }

    /*
     * Parsing
     */

    @NonCPS
    private Map parseSbomContent(String content, String format) {
        List<Map> components = []
        int totalComponents = 0

        if (!ValidationUtils.isNonEmpty(content)) {
            return [components: components, totalComponents: 0]
        }

        try {
            def parsed = new groovy.json.JsonSlurper().parseText(content)

            if ("cyclonedx".equalsIgnoreCase(format) || parsed.bomFormat?.toString()?.contains("CycloneDX")) {
                List comps = parsed.components ?: []
                for (Object compObj : comps) {
                    if (!(compObj instanceof Map)) continue
                    Map comp = (Map) compObj
                    components.add([
                        type: comp.type?.toString() ?: "library",
                        name: comp.name?.toString() ?: "",
                        version: comp.version?.toString() ?: "",
                        group: comp.group?.toString() ?: "",
                        purl: comp.purl?.toString() ?: "",
                        licenses: extractLicenses((List) comp.get("licenses") ?: []),
                        hashes: extractHashes((List) comp.get("hashes") ?: []),
                        supplier: comp.supplier?.name?.toString() ?: ""
                    ])
                }
            } else if ("spdx".equalsIgnoreCase(format) || parsed.spdxVersion instanceof String) {
                List packages = parsed.packages ?: []
                for (Object pkgObj : packages) {
                    if (!(pkgObj instanceof Map)) continue
                    Map pkg = (Map) pkgObj
                    components.add([
                        type: "library",
                        name: pkg.name?.toString() ?: pkg.SPDXID?.toString() ?: "",
                        version: pkg.versionInfo?.toString() ?: "",
                        group: pkg.supplier?.toString() ?: "",
                        purl: extractExternalRef((List) pkg.get("externalRefs") ?: []),
                        licenses: extractSpdxLicenses((List) pkg.get("licenseConcluded") ?: []),
                        checksums: extractChecksums((List) pkg.get("checksums") ?: [])
                    ])
                }
            }

            totalComponents = components.size()

        } catch (Exception e) {
            LoggingUtils.warn("SBOMGenerationManager",
                "Failed to parse SBOM content: ${e.message}")
        }

        return [components: components, totalComponents: totalComponents]
    }

    @NonCPS
    private List<String> extractLicenses(List licenses) {
        List<String> result = []
        if (licenses == null) return result
        for (Object licObj : licenses) {
            if (licObj instanceof Map) {
                Map lic = (Map) licObj
                Object licId = lic.get("id") ?: lic.get("license").id ?: lic.get("license").name
                if (licId instanceof String) result.add(licId.toString())
            }
        }
        return result
    }

    @NonCPS
    private List<String> extractHashes(List hashes) {
        List<String> result = []
        if (hashes == null) return result
        for (Object hashObj : hashes) {
            if (hashObj instanceof Map) {
                Object content = ((Map) hashObj).get("content")
                if (content instanceof String) result.add(content.toString())
            }
        }
        return result
    }

    @NonCPS
    private String extractExternalRef(List refs) {
        if (refs == null) return ""
        for (Object refObj : refs) {
            if (refObj instanceof Map) {
                Map ref = (Map) refObj
                if ("purl".equalsIgnoreCase(ref.referenceType?.toString() ?: "")) {
                    return ref.locator?.toString() ?: ""
                }
            }
        }
        return ""
    }

    @NonCPS
    private List<String> extractSpdxLicenses(List licenses) {
        List<String> result = []
        if (licenses == null) return result
        for (Object licObj : licenses) {
            if (licObj instanceof String) result.add(licObj.toString())
        }
        return result
    }

    @NonCPS
    private List<String> extractChecksums(List checksums) {
        List<String> result = []
        if (checksums == null) return result
        for (Object csObj : checksums) {
            if (csObj instanceof Map) {
                Object value = ((Map) csObj).get("value")
                if (value instanceof String) result.add(value.toString())
            }
        }
        return result
    }

    /*
     * Private helpers
     */

    private String readSbomFile(String sbomFile) {
        try {
            if (steps.fileExists(sbomFile)) {
                return steps.readFile(file: sbomFile, encoding: "UTF-8")
            }
            String workspace = steps.env.WORKSPACE ?: "."
            String altPath = "${workspace}/target/${sbomFile}"
            if (steps.fileExists(altPath)) {
                return steps.readFile(file: altPath, encoding: "UTF-8")
            }
            LoggingUtils.warn("SBOMGenerationManager",
                "SBOM file not found at '${sbomFile}' or '${altPath}' [correlationId=${correlationId}]")
            return null
        } catch (Exception e) {
            LoggingUtils.warn("SBOMGenerationManager",
                "Failed to read SBOM file: ${e.message}")
            return null
        }
    }

    @NonCPS
    private String resolveFormat(Map options) {
        if (options.format instanceof String) {
            String fmt = options.format.toString().toLowerCase()
            if (VALID_FORMATS.contains(fmt)) return fmt
        }
        return "cyclonedx"
    }

    @NonCPS
    private String resolveOutputFormat(Map options) {
        if (options.outputFormat instanceof String) {
            String of = options.outputFormat.toString().toLowerCase()
            if (VALID_OUTPUT_FORMATS.contains(of)) return of
        }
        return "json"
    }

    @NonCPS
    private int resolveTimeout(Map options) {
        if (options.timeoutMs instanceof Number) {
            return ((Number) options.timeoutMs).intValue()
        }
        return 600000
    }
}
