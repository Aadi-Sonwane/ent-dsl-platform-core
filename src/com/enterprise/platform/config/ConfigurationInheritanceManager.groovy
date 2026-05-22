package com.enterprise.platform.config

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class ConfigurationInheritanceManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final Map<String, Object> GLOBAL_DEFAULTS = createGlobalDefaults()

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    ConfigurationInheritanceManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    ConfigurationInheritanceManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map resolve(Map projectConfig) {
        LoggingUtils.info("ConfigurationInheritanceManager",
            "Resolving configuration inheritance chain [correlationId=${correlationId}]")

        if (projectConfig == null || projectConfig.isEmpty()) {
            String errMsg = "Project configuration is null or empty; cannot resolve inheritance."
            audit.emitAuditEvent("INHERITANCE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("inheritance", "failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        try {
            Map resolvedConfig = deepCloneMap(GLOBAL_DEFAULTS)

            String profile = extractProfile(projectConfig)

            Map profileDefaults = loadProfileDefaults(profile)
            resolvedConfig = deepMerge(resolvedConfig, profileDefaults)

            Map tenantDefaults = extractTenantDefaults(projectConfig)
            if (tenantDefaults != null && !tenantDefaults.isEmpty()) {
                resolvedConfig = deepMerge(resolvedConfig, tenantDefaults)
            }

            Map parentConfig = resolveParentConfig(projectConfig)
            if (parentConfig != null && !parentConfig.isEmpty()) {
                resolvedConfig = deepMerge(resolvedConfig, parentConfig)
            }

            resolvedConfig = deepMerge(resolvedConfig, projectConfig)

            resolvedConfig["_meta"] = createMetadata(projectConfig, profile, tenantDefaults, parentConfig)

            LoggingUtils.info("ConfigurationInheritanceManager",
                "Configuration resolved with inheritance chain: global → profile[${profile}] → tenant → parent → project [correlationId=${correlationId}]")

            audit.emitAuditEvent("INHERITANCE_RESOLVED",
                "Configuration inheritance resolved for profile '${profile}'", correlationId)
            telemetry.emitEvent("inheritance", "resolved", [
                correlationId: correlationId,
                profile: profile,
                hadTenantDefaults: tenantDefaults != null && !tenantDefaults.isEmpty(),
                hadParentConfig: parentConfig != null && !parentConfig.isEmpty()
            ])

            return resolvedConfig

        } catch (Exception e) {
            String errMsg = "Configuration inheritance resolution failed: ${e.message}"
            LoggingUtils.error("ConfigurationInheritanceManager", errMsg, e)
            audit.emitAuditEvent("INHERITANCE_ERROR", errMsg, correlationId)
            telemetry.emitEvent("inheritance", "error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    @NonCPS
    private static Map<String, Object> createGlobalDefaults() {
        Map defaults = [:]
        defaults["project"] = [
            metadata: [
                jdkVersion: "17",
                mavenVersion: "3.9.6",
                timezone: "UTC",
                scmCredentialsId: "git"
            ],
            build: [
                skipTests: false,
                failOnError: true,
                parallelStages: false
            ]
        ]
        defaults["branchGovernance"] = [
            include: [".*"],
            exclude: []
        ]
        defaults["rbac"] = [
            admins: [],
            developers: []
        ]
        defaults["nexus"] = [
            blobStore: "default-store",
            repositories: []
        ]
        defaults["quality"] = [
            enabled: true,
            coverage: [
                threshold: 80
            ],
            sonarQube: [
                enabled: true,
                qualityGateTimeoutMs: 300000
            ]
        ]
        defaults["security"] = [
            enabled: true,
            trivy: [
                enabled: true,
                severityThreshold: "HIGH"
            ],
            dependencyScan: [
                enabled: true
            ],
            secretScan: [
                enabled: true,
                failOnCritical: true
            ],
            compliance: [
                enabled: true
            ],
            sbom: [
                enabled: true
            ],
            signing: [
                enabled: true
            ]
        ]
        defaults["notifications"] = [
            email: [
                enabled: false
            ],
            slack: [
                enabled: false
            ]
        ]
        defaults["repositories"] = [:]
        return defaults
    }

    @NonCPS
    private String extractProfile(Map config) {
        Map project = extractMapSafely(config, "project")
        if (project != null) {
            Object profileRaw = project.get("profile")
            if (profileRaw instanceof String && ValidationUtils.isNonEmpty((String) profileRaw)) {
                return (String) profileRaw
            }
            Map metadata = extractMapSafely(project, "metadata")
            if (metadata != null) {
                Object envRaw = metadata.get("environment")
                if (envRaw instanceof String && ValidationUtils.isNonEmpty((String) envRaw)) {
                    return (String) envRaw
                }
            }
        }
        return "default"
    }

    @NonCPS
    private Map loadProfileDefaults(String profile) {
        if (!ValidationUtils.isNonEmpty(profile) || "default".equals(profile)) {
            return [:]
        }
        switch (profile) {
            case "development":
                return [
                    project: [
                        metadata: [
                            environment: "development"
                        ],
                        build: [
                            skipTests: false,
                            failOnError: true
                        ]
                    ],
                    quality: [
                        enabled: true,
                        coverage: [threshold: 60]
                    ],
                    security: [
                        enabled: true,
                        trivy: [severityThreshold: "CRITICAL"]
                    ]
                ]
            case "staging":
                return [
                    project: [
                        metadata: [
                            environment: "staging"
                        ],
                        build: [
                            skipTests: false,
                            failOnError: true
                        ]
                    ],
                    quality: [
                        enabled: true,
                        coverage: [threshold: 75]
                    ],
                    security: [
                        enabled: true,
                        trivy: [severityThreshold: "HIGH"]
                    ]
                ]
            case "production":
                return [
                    project: [
                        metadata: [
                            environment: "production"
                        ],
                        build: [
                            skipTests: false,
                            failOnError: true
                        ]
                    ],
                    quality: [
                        enabled: true,
                        coverage: [threshold: 85]
                    ],
                    security: [
                        enabled: true,
                        trivy: [severityThreshold: "MEDIUM"]
                    ]
                ]
            default:
                LoggingUtils.warn("ConfigurationInheritanceManager",
                    "Unknown profile '${profile}'. Using empty profile defaults [correlationId=${correlationId}]")
                return [:]
        }
    }

    @NonCPS
    private Map extractTenantDefaults(Map config) {
        Map project = extractMapSafely(config, "project")
        if (project == null) return null
        Object tenantDefaultsRaw = project.get("tenantDefaults")
        if (tenantDefaultsRaw instanceof Map) {
            return (Map) tenantDefaultsRaw
        }
        return null
    }

    @NonCPS
    private Map resolveParentConfig(Map config) {
        Map project = extractMapSafely(config, "project")
        if (project == null) return null

        Map metadata = extractMapSafely(project, "metadata")
        if (metadata == null) return null

        Object parentRefRaw = metadata.get("parentConfig")
        if (parentRefRaw instanceof String && ValidationUtils.isNonEmpty((String) parentRefRaw)) {
            String parentConfigPath = (String) parentRefRaw
            LoggingUtils.info("ConfigurationInheritanceManager",
                "Parent configuration reference '${parentConfigPath}' found, but external resolution requires file system access [correlationId=${correlationId}]")
            addWarning("Parent configuration reference '${parentConfigPath}' was declared but external parent resolution is not available in this context.")
        }
        return null
    }

    @NonCPS
    private Map deepMerge(Map base, Map override) {
        if (base == null && override == null) return [:]
        if (base == null) return deepCloneMap(override)
        if (override == null) return deepCloneMap(base)

        Map result = deepCloneMap(base)

        for (Map.Entry entry : override.entrySet()) {
            String key = entry.key.toString()
            Object overrideValue = entry.value
            Object baseValue = result.get(key)

            if (overrideValue instanceof Map && baseValue instanceof Map) {
                result[key] = deepMerge((Map) baseValue, (Map) overrideValue)
            } else if (overrideValue instanceof List && baseValue instanceof List) {
                List mergedList = new ArrayList((List) baseValue)
                mergedList.addAll((List) overrideValue)
                result[key] = mergedList
            } else if (overrideValue != null) {
                result[key] = overrideValue
            }
        }

        return result
    }

    @NonCPS
    private Map deepCloneMap(Map source) {
        if (source == null) return [:]
        Map result = [:]
        for (Map.Entry entry : source.entrySet()) {
            String key = entry.key.toString()
            Object value = entry.value
            if (value instanceof Map) {
                result[key] = deepCloneMap((Map) value)
            } else if (value instanceof List) {
                result[key] = deepCloneList((List) value)
            } else {
                result[key] = value
            }
        }
        return result
    }

    @NonCPS
    private List deepCloneList(List source) {
        if (source == null) return []
        List result = []
        for (Object item : source) {
            if (item instanceof Map) {
                result.add(deepCloneMap((Map) item))
            } else if (item instanceof List) {
                result.add(deepCloneList((List) item))
            } else {
                result.add(item)
            }
        }
        return result
    }

    @NonCPS
    private Map extractMapSafely(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof Map) return (Map) value
        return null
    }

    @NonCPS
    private Map createMetadata(Map originalConfig, String profile, Map tenantDefaults, Map parentConfig) {
        Map metadata = [:]
        metadata["inheritanceProfile"] = profile
        metadata["inheritedFromParent"] = parentConfig != null
        metadata["hadTenantDefaults"] = tenantDefaults != null

        Map project = extractMapSafely(originalConfig, "project")
        Map originalMeta = project != null ? extractMapSafely(project, "metadata") : null
        if (originalMeta != null) {
            metadata["originalBusinessUnit"] = originalMeta.get("businessUnit")
            metadata["originalProjectName"] = originalMeta.get("projectName")
            metadata["originalTeam"] = originalMeta.get("team")
        }

        metadata["resolvedAt"] = System.currentTimeMillis()
        return metadata
    }

    @NonCPS
    private void addWarning(String message) {
        LoggingUtils.warn("ConfigurationInheritanceManager", message)
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
