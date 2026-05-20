package com.enterprise.platform.maven

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class ArtifactNamingStrategy implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> PROHIBITED_GROUP_ID_PREFIXES = ["com.sun", "com.oracle", "com.ibm"]
    private static final int MAX_GROUP_ID_SEGMENTS = 8
    private static final int MAX_ARTIFACT_ID_LENGTH = 120
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT"

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    ArtifactNamingStrategy(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    ArtifactNamingStrategy(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    String resolveGroupId(Map projectConfig) {
        LoggingUtils.info("ArtifactNamingStrategy",
            "Resolving groupId [correlationId=${correlationId}]")

        try {
            String groupId = null
            if (projectConfig != null) {
                Map maven = extractMapField(projectConfig, "maven")
                if (maven != null) {
                    groupId = extractStringField(maven, "groupId")
                }
                if (!ValidationUtils.isNonEmpty(groupId)) {
                    Map project = extractMapField(projectConfig, "project")
                    if (project != null) {
                        Map metadata = extractMapField(project, "metadata")
                        if (metadata != null) {
                            String businessUnit = extractStringField(metadata, "businessUnit")
                            String team = extractStringField(metadata, "team")
                            String projectName = extractStringField(metadata, "projectName")
                            if (ValidationUtils.isNonEmpty(businessUnit) && ValidationUtils.isNonEmpty(projectName)) {
                                groupId = buildDefaultGroupId(businessUnit, team, projectName)
                            }
                        }
                    }
                }
            }

            if (!ValidationUtils.isNonEmpty(groupId)) {
                groupId = "com.enterprise.buildos"
                LoggingUtils.warn("ArtifactNamingStrategy",
                    "No groupId could be resolved from configuration. Using default: '${groupId}' [correlationId=${correlationId}]")
            }

            String validated = validateAndNormalizeGroupId(groupId)

            LoggingUtils.info("ArtifactNamingStrategy",
                "Resolved groupId: '${validated}' [correlationId=${correlationId}]")
            return validated

        } catch (Exception e) {
            String errMsg = "Failed to resolve groupId: ${e.message}"
            LoggingUtils.error("ArtifactNamingStrategy", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    String resolveArtifactId(Map projectConfig) {
        LoggingUtils.info("ArtifactNamingStrategy",
            "Resolving artifactId [correlationId=${correlationId}]")

        try {
            String artifactId = null
            if (projectConfig != null) {
                Map maven = extractMapField(projectConfig, "maven")
                if (maven != null) {
                    artifactId = extractStringField(maven, "artifactId")
                }
                if (!ValidationUtils.isNonEmpty(artifactId)) {
                    Map project = extractMapField(projectConfig, "project")
                    if (project != null) {
                        Map metadata = extractMapField(project, "metadata")
                        if (metadata != null) {
                            artifactId = extractStringField(metadata, "projectName")
                        }
                    }
                }
            }

            if (!ValidationUtils.isNonEmpty(artifactId)) {
                artifactId = "buildos-application"
                LoggingUtils.warn("ArtifactNamingStrategy",
                    "No artifactId could be resolved from configuration. Using default: '${artifactId}' [correlationId=${correlationId}]")
            }

            String validated = validateAndNormalizeArtifactId(artifactId)

            LoggingUtils.info("ArtifactNamingStrategy",
                "Resolved artifactId: '${validated}' [correlationId=${correlationId}]")
            return validated

        } catch (Exception e) {
            String errMsg = "Failed to resolve artifactId: ${e.message}"
            LoggingUtils.error("ArtifactNamingStrategy", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    String resolveVersion(Map projectConfig) {
        LoggingUtils.info("ArtifactNamingStrategy",
            "Resolving version [correlationId=${correlationId}]")

        try {
            String version = null
            if (projectConfig != null) {
                Map maven = extractMapField(projectConfig, "maven")
                if (maven != null) {
                    version = extractStringField(maven, "version")
                }
                Map project = extractMapField(projectConfig, "project")
                if (project != null) {
                    Map metadata = extractMapField(project, "metadata")
                    if (metadata != null) {
                        String configVersion = extractStringField(metadata, "version")
                        if (ValidationUtils.isNonEmpty(configVersion)) {
                            version = configVersion
                        }
                    }
                }
            }

            if (!ValidationUtils.isNonEmpty(version)) {
                version = "1.0.0"
                LoggingUtils.warn("ArtifactNamingStrategy",
                    "No version could be resolved from configuration. Using default: '${version}' [correlationId=${correlationId}]")
            }

            boolean isSnapshot = detectSnapshotVersion(projectConfig)

            if (isSnapshot && !version.endsWith(SNAPSHOT_SUFFIX)) {
                version = version + SNAPSHOT_SUFFIX
                LoggingUtils.info("ArtifactNamingStrategy",
                    "Appended -SNAPSHOT suffix to version: '${version}' [correlationId=${correlationId}]")
            }

            LoggingUtils.info("ArtifactNamingStrategy",
                "Resolved version: '${version}' [correlationId=${correlationId}]")
            return version

        } catch (Exception e) {
            String errMsg = "Failed to resolve version: ${e.message}"
            LoggingUtils.error("ArtifactNamingStrategy", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    String buildClassifier(String baseClassifier, Map projectConfig) {
        if (!ValidationUtils.isNonEmpty(baseClassifier)) {
            return null
        }

        try {
            String normalized = baseClassifier.toLowerCase().trim()
            if ("sources".equals(normalized) || "javadoc".equals(normalized) ||
                "tests".equals(normalized) || "test".equals(normalized) ||
                "distribution".equals(normalized) || "assembly".equals(normalized)) {
                return normalized
            }

            String branchName = resolveBranchName(projectConfig)
            if (ValidationUtils.isNonEmpty(branchName)) {
                return "${normalized}-${branchName}"
            }

            return normalized

        } catch (Exception e) {
            LoggingUtils.warn("ArtifactNamingStrategy",
                "Failed to build classifier '${baseClassifier}': ${e.message}")
            return baseClassifier
        }
    }

    String buildFinalArtifactName(Map projectConfig) {
        try {
            String artifactId = resolveArtifactId(projectConfig)
            String version = resolveVersion(projectConfig)
            String classifier = resolveClassifier(projectConfig)
            String extension = resolveExtension(projectConfig)

            StringBuilder name = new StringBuilder()
            name.append(artifactId).append("-").append(version)
            if (ValidationUtils.isNonEmpty(classifier)) {
                name.append("-").append(classifier)
            }
            if (ValidationUtils.isNonEmpty(extension)) {
                name.append(".").append(extension)
            } else {
                name.append(".jar")
            }

            return name.toString()

        } catch (Exception e) {
            String errMsg = "Failed to build final artifact name: ${e.message}"
            LoggingUtils.error("ArtifactNamingStrategy", errMsg, e)
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
    private String buildDefaultGroupId(String businessUnit, String team, String projectName) {
        StringBuilder sb = new StringBuilder("com")
        String bu = businessUnit.toLowerCase().replaceAll("[^a-z0-9]", "")
        if (ValidationUtils.isNonEmpty(bu)) {
            sb.append(".").append(bu)
        }
        if (ValidationUtils.isNonEmpty(team)) {
            String t = team.toLowerCase().replaceAll("[^a-z0-9]", "")
            if (ValidationUtils.isNonEmpty(t)) {
                sb.append(".").append(t)
            }
        }
        String pn = projectName.toLowerCase().replaceAll("[^a-z0-9]", "")
        if (ValidationUtils.isNonEmpty(pn)) {
            sb.append(".").append(pn)
        }
        return sb.toString()
    }

    @NonCPS
    private String validateAndNormalizeGroupId(String groupId) {
        if (!ValidationUtils.isNonEmpty(groupId)) {
            throw new IllegalArgumentException("groupId must not be null or empty")
        }

        String normalized = groupId.trim().toLowerCase()

        String[] segments = normalized.split("\\.")
        if (segments.length < 2) {
            throw new IllegalArgumentException(
                "groupId '${normalized}' must have at least 2 segments (e.g. com.example)")
        }
        if (segments.length > MAX_GROUP_ID_SEGMENTS) {
            throw new IllegalArgumentException(
                "groupId '${normalized}' has ${segments.length} segments, exceeds maximum of ${MAX_GROUP_ID_SEGMENTS}")
        }

        for (String segment : segments) {
            if (!segment.matches("^[a-z0-9][a-z0-9_]*$")) {
                throw new IllegalArgumentException(
                    "groupId segment '${segment}' must start with a lowercase letter or digit and contain only lowercase alphanumeric or underscores")
            }
        }

        for (String prefix : PROHIBITED_GROUP_ID_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                throw new IllegalArgumentException(
                    "groupId '${normalized}' uses prohibited prefix '${prefix}'. These namespaces are reserved.")
            }
        }

        return normalized
    }

    @NonCPS
    private String validateAndNormalizeArtifactId(String artifactId) {
        if (!ValidationUtils.isNonEmpty(artifactId)) {
            throw new IllegalArgumentException("artifactId must not be null or empty")
        }

        String normalized = artifactId.trim().toLowerCase()
        if (normalized.length() > MAX_ARTIFACT_ID_LENGTH) {
            throw new IllegalArgumentException(
                "artifactId '${normalized}' exceeds maximum length of ${MAX_ARTIFACT_ID_LENGTH}")
        }

        if (!normalized.matches("^[a-z][a-z0-9_-]*$")) {
            throw new IllegalArgumentException(
                "artifactId '${normalized}' must start with a lowercase letter and contain only lowercase alphanumeric, underscore, or hyphen characters")
        }

        return normalized
    }

    @NonCPS
    private Boolean detectSnapshotVersion(Map projectConfig) {
        if (projectConfig == null) return true
        Map maven = extractMapField(projectConfig, "maven")
        if (maven != null) {
            Object snapshotRaw = maven.get("snapshot")
            if (snapshotRaw instanceof Boolean) {
                return (Boolean) snapshotRaw
            }
        }
        Map project = extractMapField(projectConfig, "project")
        if (project != null) {
            Map metadata = extractMapField(project, "metadata")
            if (metadata != null) {
                String env = extractStringField(metadata, "environment")
                if ("production".equals(env)) return false
            }
        }
        return true
    }

    @NonCPS
    private String resolveClassifier(Map projectConfig) {
        if (projectConfig == null) return null
        Map maven = extractMapField(projectConfig, "maven")
        if (maven != null) {
            return extractStringField(maven, "classifier")
        }
        return null
    }

    @NonCPS
    private String resolveExtension(Map projectConfig) {
        if (projectConfig == null) return "jar"
        Map maven = extractMapField(projectConfig, "maven")
        if (maven != null) {
            String ext = extractStringField(maven, "extension")
            if (ValidationUtils.isNonEmpty(ext)) return ext
            String packaging = extractStringField(maven, "packaging")
            if ("war".equals(packaging)) return "war"
            if ("ear".equals(packaging)) return "ear"
            if ("pom".equals(packaging)) return "pom"
        }
        return "jar"
    }

    @NonCPS
    private String resolveBranchName(Map projectConfig) {
        if (projectConfig == null) return null
        Map envMap = extractMapField(projectConfig, "environment")
        if (envMap != null) {
            return extractStringField(envMap, "branchName")
        }
        return null
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
