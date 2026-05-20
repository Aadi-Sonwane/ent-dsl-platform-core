package com.enterprise.platform.maven

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class DistributionManagementGenerator implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String SNAPSHOT_REPO_ID = "nexus-snapshots"
    private static final String RELEASE_REPO_ID = "nexus-releases"

    private final Object steps
    private final String correlationId

    DistributionManagementGenerator(Object steps) {
        this.steps = steps
        this.correlationId = java.util.UUID.randomUUID().toString()
    }

    DistributionManagementGenerator(Object steps, String correlationId) {
        this.steps = steps
        this.correlationId = correlationId
    }

    String generateDistributionManagementXml(String nexusUrl, Map projectConfig) {
        LoggingUtils.info("DistributionManagementGenerator",
            "Generating distributionManagement XML [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(nexusUrl)) {
            String errMsg = "Nexus URL must not be null or empty for distributionManagement generation"
            LoggingUtils.error("DistributionManagementGenerator", errMsg, null)
            throw new IllegalArgumentException(errMsg)
        }

        String normalizedNexusUrl = normalizeUrl(nexusUrl)

        try {
            String snapshotRepoUrl = resolveSnapshotRepoUrl(normalizedNexusUrl, projectConfig)
            String releaseRepoUrl = resolveReleaseRepoUrl(normalizedNexusUrl, projectConfig)
            String snapshotRepoId = resolveSnapshotRepoId(projectConfig)
            String releaseRepoId = resolveReleaseRepoId(projectConfig)

            String xml = buildDistributionManagementXml(
                snapshotRepoId, snapshotRepoUrl, releaseRepoId, releaseRepoUrl)

            LoggingUtils.info("DistributionManagementGenerator",
                "distributionManagement XML generated (${xml.length()} chars) [correlationId=${correlationId}]")

            return xml

        } catch (Exception e) {
            String errMsg = "Failed to generate distributionManagement XML: ${e.message}"
            LoggingUtils.error("DistributionManagementGenerator", errMsg, e)
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
    private String resolveSnapshotRepoUrl(String nexusUrl, Map config) {
        if (config != null) {
            Map distribution = extractMapField(config, "distribution")
            if (distribution != null) {
                String customUrl = extractStringField(distribution, "snapshotRepositoryUrl")
                if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
            }
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map distMgmt = extractMapField(maven, "distributionManagement")
                if (distMgmt != null) {
                    String customUrl = extractStringField(distMgmt, "snapshotUrl")
                    if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
                }
            }
        }
        return "${nexusUrl}/repository/maven-snapshots/"
    }

    @NonCPS
    private String resolveReleaseRepoUrl(String nexusUrl, Map config) {
        if (config != null) {
            Map distribution = extractMapField(config, "distribution")
            if (distribution != null) {
                String customUrl = extractStringField(distribution, "releaseRepositoryUrl")
                if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
            }
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map distMgmt = extractMapField(maven, "distributionManagement")
                if (distMgmt != null) {
                    String customUrl = extractStringField(distMgmt, "releaseUrl")
                    if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
                }
            }
        }
        return "${nexusUrl}/repository/maven-releases/"
    }

    @NonCPS
    private String resolveSnapshotRepoId(Map config) {
        if (config != null) {
            Map distribution = extractMapField(config, "distribution")
            if (distribution != null) {
                String customId = extractStringField(distribution, "snapshotRepositoryId")
                if (ValidationUtils.isNonEmpty(customId)) return customId
            }
        }
        return SNAPSHOT_REPO_ID
    }

    @NonCPS
    private String resolveReleaseRepoId(Map config) {
        if (config != null) {
            Map distribution = extractMapField(config, "distribution")
            if (distribution != null) {
                String customId = extractStringField(distribution, "releaseRepositoryId")
                if (ValidationUtils.isNonEmpty(customId)) return customId
            }
        }
        return RELEASE_REPO_ID
    }

    @NonCPS
    private String buildDistributionManagementXml(
            String snapshotRepoId, String snapshotRepoUrl,
            String releaseRepoId, String releaseRepoUrl) {

        StringBuilder xml = new StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<project xmlns=\"http://maven.apache.org/POM/4.0.0\"\n")
        xml.append("         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        xml.append("         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 ")
        xml.append("http://maven.apache.org/xsd/maven-4.0.0.xsd\">\n")
        xml.append("  <distributionManagement>\n")
        xml.append("    <snapshotRepository>\n")
        xml.append("      <id>").append(escapeXml(snapshotRepoId)).append("</id>\n")
        xml.append("      <url>").append(escapeXml(snapshotRepoUrl)).append("</url>\n")
        xml.append("      <uniqueVersion>true</uniqueVersion>\n")
        xml.append("    </snapshotRepository>\n")
        xml.append("    <repository>\n")
        xml.append("      <id>").append(escapeXml(releaseRepoId)).append("</id>\n")
        xml.append("      <url>").append(escapeXml(releaseRepoUrl)).append("</url>\n")
        xml.append("      <uniqueVersion>false</uniqueVersion>\n")
        xml.append("    </repository>\n")
        xml.append("  </distributionManagement>\n")
        xml.append("</project>\n")

        return xml.toString()
    }

    @NonCPS
    private String normalizeUrl(String url) {
        if (url == null) return ""
        String normalized = url.trim()
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1)
        }
        return normalized
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
