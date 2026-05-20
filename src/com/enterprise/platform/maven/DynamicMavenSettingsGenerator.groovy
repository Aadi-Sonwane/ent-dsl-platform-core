package com.enterprise.platform.maven

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class DynamicMavenSettingsGenerator implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String SETTINGS_NAMESPACE = "http://maven.apache.org/SETTINGS/1.0.0"
    private static final String SETTINGS_SCHEMA_LOCATION =
        "http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd"
    private static final String NEXUS_SNAPSHOT_REPO_ID = "nexus-snapshots"
    private static final String NEXUS_RELEASE_REPO_ID = "nexus-releases"
    private static final String NEXUS_MIRROR_ID = "nexus-mirror"
    private static final String NEXUS_PROFILE_ID = "nexus-profile"

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    DynamicMavenSettingsGenerator(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    DynamicMavenSettingsGenerator(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    String generateSettingsXml(String nexusUrl, Map projectConfig) {
        LoggingUtils.info("DynamicMavenSettingsGenerator",
            "Generating Maven settings.xml in-memory [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(nexusUrl)) {
            String errMsg = "Nexus URL is required for settings.xml generation"
            audit.emitAuditEvent("SETTINGS_GEN_FAILED", errMsg, correlationId)
            throw new IllegalArgumentException(errMsg)
        }

        String normalizedNexusUrl = normalizeUrl(nexusUrl)

        try {
            String snapshotRepoUrl = resolveSnapshotRepoUrl(normalizedNexusUrl, projectConfig)
            String releaseRepoUrl = resolveReleaseRepoUrl(normalizedNexusUrl, projectConfig)
            String publicGroupUrl = resolvePublicGroupUrl(normalizedNexusUrl, projectConfig)
            String snapshotRepoId = resolveSnapshotRepoId(projectConfig)
            String releaseRepoId = resolveReleaseRepoId(projectConfig)
            String mirrorId = resolveMirrorId(projectConfig)
            String profileId = resolveProfileId(projectConfig)
            Map<String, String> serverCredentials = resolveServerCredentials(projectConfig)
            Boolean enablePluginRepositories = resolvePluginRepositoryFlag(projectConfig)

            String xml = buildSettingsXml(
                normalizedNexusUrl, publicGroupUrl,
                snapshotRepoUrl, releaseRepoUrl,
                snapshotRepoId, releaseRepoId,
                mirrorId, profileId,
                serverCredentials, enablePluginRepositories)

            String validationResult = validateWellFormed(xml)
            if (validationResult != null) {
                LoggingUtils.warn("DynamicMavenSettingsGenerator",
                    "settings.xml validation: ${validationResult} [correlationId=${correlationId}]")
            }

            LoggingUtils.info("DynamicMavenSettingsGenerator",
                "settings.xml generated in-memory (${xml.length()} chars) [correlationId=${correlationId}]")

            audit.emitAuditEvent("SETTINGS_GEN_SUCCESS",
                "Maven settings.xml generated (${xml.length()} chars, mirrorOf=*)", correlationId)
            telemetry.emitEvent("maven.settings", "generated", [
                correlationId: correlationId,
                nexusUrl: maskUrl(normalizedNexusUrl),
                xmlLength: xml.length(),
                profileId: profileId
            ])

            return xml

        } catch (Exception e) {
            String errMsg = "Failed to generate settings.xml: ${e.message}"
            LoggingUtils.error("DynamicMavenSettingsGenerator", errMsg, e)
            audit.emitAuditEvent("SETTINGS_GEN_FAILED", errMsg, correlationId)
            telemetry.emitEvent("maven.settings", "failed", [
                correlationId: correlationId,
                error: e.message
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * XML Builder
     */

    @NonCPS
    private String buildSettingsXml(
            String nexusUrl, String publicGroupUrl,
            String snapshotRepoUrl, String releaseRepoUrl,
            String snapshotRepoId, String releaseRepoId,
            String mirrorId, String profileId,
            Map<String, String> serverCredentials,
            Boolean enablePluginRepositories) {

        StringBuilder xml = new StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<settings xmlns=\"").append(SETTINGS_NAMESPACE).append("\"\n")
        xml.append("          xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n")
        xml.append("          xsi:schemaLocation=\"").append(SETTINGS_SCHEMA_LOCATION).append("\">\n")

        xml.append("  <localRepository>").append("\${user.home}/.m2/repository</localRepository>\n")
        xml.append("  <interactiveMode>false</interactiveMode>\n")
        xml.append("  <offline>false</offline>\n")

        /*
         * Servers section — credentials for snapshot and release repos.
         * Values reference environment variables injected by withCredentials.
         */
        xml.append("  <servers>\n")
        xml.append("    <server>\n")
        xml.append("      <id>").append(escapeXml(snapshotRepoId)).append("</id>\n")
        if (serverCredentials.containsKey("username") && serverCredentials.containsKey("password")) {
            xml.append("      <username>").append(escapeXml(serverCredentials.get("username"))).append("</username>\n")
            xml.append("      <password>").append(escapeXml(serverCredentials.get("password"))).append("</password>\n")
        } else {
            xml.append("      <username>").append("\${env.NEXUS_USER}").append("</username>\n")
            xml.append("      <password>").append("\${env.NEXUS_PASS}").append("</password>\n")
        }
        xml.append("      <configuration>\n")
        xml.append("        <httpConfiguration>\n")
        xml.append("          <all>\n")
        xml.append("            <connectionTimeout>60000</connectionTimeout>\n")
        xml.append("            <readTimeout>120000</readTimeout>\n")
        xml.append("          </all>\n")
        xml.append("        </httpConfiguration>\n")
        xml.append("      </configuration>\n")
        xml.append("    </server>\n")
        xml.append("    <server>\n")
        xml.append("      <id>").append(escapeXml(releaseRepoId)).append("</id>\n")
        if (serverCredentials.containsKey("username") && serverCredentials.containsKey("password")) {
            xml.append("      <username>").append(escapeXml(serverCredentials.get("username"))).append("</username>\n")
            xml.append("      <password>").append(escapeXml(serverCredentials.get("password"))).append("</password>\n")
        } else {
            xml.append("      <username>").append("\${env.NEXUS_USER}").append("</username>\n")
            xml.append("      <password>").append("\${env.NEXUS_PASS}").append("</password>\n")
        }
        xml.append("      <configuration>\n")
        xml.append("        <httpConfiguration>\n")
        xml.append("          <all>\n")
        xml.append("            <connectionTimeout>60000</connectionTimeout>\n")
        xml.append("            <readTimeout>120000</readTimeout>\n")
        xml.append("          </all>\n")
        xml.append("        </httpConfiguration>\n")
        xml.append("      </configuration>\n")
        xml.append("    </server>\n")
        xml.append("  </servers>\n")

        /*
         * Mirrors section — mirrorOf=* forces ALL traffic through Nexus.
         * This blocks any direct Maven Central / JCenter / etc access.
         */
        xml.append("  <mirrors>\n")
        xml.append("    <mirror>\n")
        xml.append("      <id>").append(escapeXml(mirrorId)).append("</id>\n")
        xml.append("      <mirrorOf>*</mirrorOf>\n")
        xml.append("      <name>Corporate Nexus Repository Mirror</name>\n")
        xml.append("      <url>").append(escapeXml(publicGroupUrl)).append("</url>\n")
        xml.append("      <blocked>false</blocked>\n")
        xml.append("    </mirror>\n")
        xml.append("  </mirrors>\n")

        /*
         * Profiles section — defines repositories for snapshots and releases
         */
        xml.append("  <profiles>\n")
        xml.append("    <profile>\n")
        xml.append("      <id>").append(escapeXml(profileId)).append("</id>\n")
        xml.append("      <activation>\n")
        xml.append("        <activeByDefault>true</activeByDefault>\n")
        xml.append("      </activation>\n")
        xml.append("      <repositories>\n")
        xml.append("        <repository>\n")
        xml.append("          <id>").append(escapeXml(snapshotRepoId)).append("</id>\n")
        xml.append("          <url>").append(escapeXml(snapshotRepoUrl)).append("</url>\n")
        xml.append("          <snapshots>\n")
        xml.append("            <enabled>true</enabled>\n")
        xml.append("            <updatePolicy>always</updatePolicy>\n")
        xml.append("            <checksumPolicy>fail</checksumPolicy>\n")
        xml.append("          </snapshots>\n")
        xml.append("          <releases>\n")
        xml.append("            <enabled>false</enabled>\n")
        xml.append("          </releases>\n")
        xml.append("        </repository>\n")
        xml.append("        <repository>\n")
        xml.append("          <id>").append(escapeXml(releaseRepoId)).append("</id>\n")
        xml.append("          <url>").append(escapeXml(releaseRepoUrl)).append("</url>\n")
        xml.append("          <snapshots>\n")
        xml.append("            <enabled>false</enabled>\n")
        xml.append("          </snapshots>\n")
        xml.append("          <releases>\n")
        xml.append("            <enabled>true</enabled>\n")
        xml.append("            <updatePolicy>daily</updatePolicy>\n")
        xml.append("            <checksumPolicy>fail</checksumPolicy>\n")
        xml.append("          </releases>\n")
        xml.append("        </repository>\n")
        xml.append("      </repositories>\n")

        if (enablePluginRepositories != null && enablePluginRepositories) {
            xml.append("      <pluginRepositories>\n")
            xml.append("        <pluginRepository>\n")
            xml.append("          <id>").append(escapeXml(snapshotRepoId)).append("</id>\n")
            xml.append("          <url>").append(escapeXml(snapshotRepoUrl)).append("</url>\n")
            xml.append("          <snapshots><enabled>true</enabled></snapshots>\n")
            xml.append("          <releases><enabled>false</enabled></releases>\n")
            xml.append("        </pluginRepository>\n")
            xml.append("        <pluginRepository>\n")
            xml.append("          <id>").append(escapeXml(releaseRepoId)).append("</id>\n")
            xml.append("          <url>").append(escapeXml(releaseRepoUrl)).append("</url>\n")
            xml.append("          <snapshots><enabled>false</enabled></snapshots>\n")
            xml.append("          <releases><enabled>true</enabled></releases>\n")
            xml.append("        </pluginRepository>\n")
            xml.append("      </pluginRepositories>\n")
        }

        xml.append("      <properties>\n")
        xml.append("        <nexus.url>").append(escapeXml(nexusUrl)).append("</nexus.url>\n")
        xml.append("        <maven.compiler.source>17</maven.compiler.source>\n")
        xml.append("        <maven.compiler.target>17</maven.compiler.target>\n")
        xml.append("        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>\n")
        xml.append("        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>\n")
        xml.append("        <failOnMissingWebXml>false</failOnMissingWebXml>\n")
        xml.append("      </properties>\n")
        xml.append("    </profile>\n")
        xml.append("  </profiles>\n")

        /*
         * Active profiles
         */
        xml.append("  <activeProfiles>\n")
        xml.append("    <activeProfile>").append(escapeXml(profileId)).append("</activeProfile>\n")
        xml.append("  </activeProfiles>\n")

        xml.append("</settings>\n")
        return xml.toString()
    }

    /*
     * URL / ID resolution
     */

    @NonCPS
    private String resolveSnapshotRepoUrl(String nexusUrl, Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customUrl = extractStringField(repos, "snapshotUrl")
                    if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
                }
            }
        }
        return "${nexusUrl}/repository/maven-snapshots/"
    }

    @NonCPS
    private String resolveReleaseRepoUrl(String nexusUrl, Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customUrl = extractStringField(repos, "releaseUrl")
                    if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
                }
            }
        }
        return "${nexusUrl}/repository/maven-releases/"
    }

    @NonCPS
    private String resolvePublicGroupUrl(String nexusUrl, Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customUrl = extractStringField(repos, "publicGroupUrl")
                    if (ValidationUtils.isNonEmpty(customUrl)) return customUrl
                }
            }
        }
        return "${nexusUrl}/repository/maven-public/"
    }

    @NonCPS
    private String resolveSnapshotRepoId(Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customId = extractStringField(repos, "snapshotId")
                    if (ValidationUtils.isNonEmpty(customId)) return customId
                }
            }
        }
        return NEXUS_SNAPSHOT_REPO_ID
    }

    @NonCPS
    private String resolveReleaseRepoId(Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customId = extractStringField(repos, "releaseId")
                    if (ValidationUtils.isNonEmpty(customId)) return customId
                }
            }
        }
        return NEXUS_RELEASE_REPO_ID
    }

    @NonCPS
    private String resolveMirrorId(Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                Map repos = extractMapField(maven, "repositories")
                if (repos != null) {
                    String customId = extractStringField(repos, "mirrorId")
                    if (ValidationUtils.isNonEmpty(customId)) return customId
                }
            }
        }
        return NEXUS_MIRROR_ID
    }

    @NonCPS
    private String resolveProfileId(Map config) {
        if (config != null) {
            Map maven = extractMapField(config, "maven")
            if (maven != null) {
                String customId = extractStringField(maven, "profileId")
                if (ValidationUtils.isNonEmpty(customId)) return customId
            }
        }
        return NEXUS_PROFILE_ID
    }

    @NonCPS
    private Map<String, String> resolveServerCredentials(Map config) {
        Map<String, String> credentials = [:]
        if (config == null) return credentials
        Map nexus = extractMapField(config, "nexus")
        if (nexus != null) {
            String user = extractStringField(nexus, "deployUsername")
            String pass = extractStringField(nexus, "deployPassword")
            if (ValidationUtils.isNonEmpty(user)) credentials["username"] = user
            if (ValidationUtils.isNonEmpty(pass)) credentials["password"] = pass
        }
        return credentials
    }

    @NonCPS
    private Boolean resolvePluginRepositoryFlag(Map config) {
        if (config == null) return false
        Map maven = extractMapField(config, "maven")
        if (maven != null) {
            Object flag = maven.get("enablePluginRepositories")
            if (flag instanceof Boolean) return (Boolean) flag
        }
        return false
    }

    /*
     * Validation
     */

    @NonCPS
    private String validateWellFormed(String xml) {
        if (xml == null || xml.isEmpty()) return "XML is null or empty"
        if (!xml.contains("<settings")) return "Missing <settings> root element"
        if (!xml.contains("</settings>")) return "Missing </settings> closing element"
        if (!xml.contains("<mirrorOf>*</mirrorOf>")) return "Missing mirrorOf=* configuration"
        if (!xml.contains("<servers>")) return "Missing <servers> section"
        if (!xml.contains("<profiles>")) return "Missing <profiles> section"
        if (!xml.contains("<activeProfiles>")) return "Missing <activeProfiles> section"
        return null
    }

    /*
     * Utility
     */

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
    private String maskUrl(String url) {
        if (url == null) return ""
        return url.replaceAll("(https?://)([^@]+@)?(.*)", "\$1***@\$3")
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
