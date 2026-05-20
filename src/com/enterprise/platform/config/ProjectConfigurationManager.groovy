package com.enterprise.platform.config

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class ProjectConfigurationManager implements Serializable {
    private static final long serialVersionUID = 1L

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final EnterpriseYAMLParser parser
    private final SchemaValidationManager schemaValidator
    private final ConfigurationInheritanceManager inheritanceManager

    private Map resolvedConfig = null
    private boolean initialized = false

    ProjectConfigurationManager(Object steps) {
        this.steps = steps
        this.correlationId = java.util.UUID.randomUUID().toString()
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.parser = new EnterpriseYAMLParser(steps, this.correlationId)
        this.schemaValidator = new SchemaValidationManager(steps, this.correlationId)
        this.inheritanceManager = new ConfigurationInheritanceManager(steps, this.correlationId)
    }

    ProjectConfigurationManager(Object steps, String correlationId) {
        this.steps = steps
        this.correlationId = correlationId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.parser = new EnterpriseYAMLParser(steps, this.correlationId)
        this.schemaValidator = new SchemaValidationManager(steps, this.correlationId)
        this.inheritanceManager = new ConfigurationInheritanceManager(steps, this.correlationId)
    }

    Map process(String yamlText) {
        LoggingUtils.info("ProjectConfigurationManager",
            "Processing project configuration [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(yamlText)) {
            String errMsg = "YAML configuration text must not be null or empty"
            audit.emitAuditEvent("CONFIG_PROCESS_FAILED", errMsg, correlationId)
            telemetry.emitEvent("config", "process_failed", [
                correlationId: correlationId,
                phase: "input_validation",
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        long startTime = System.currentTimeMillis()

        try {
            audit.emitAuditEvent("CONFIG_PROCESS_STARTED",
                "Project configuration processing started", correlationId)

            /*
             * Phase 1: Parse raw YAML into structured Map
             */
            LoggingUtils.info("ProjectConfigurationManager",
                "Phase 1: Parsing YAML [correlationId=${correlationId}]")
            Map parsedConfig
            try {
                parsedConfig = parser.parse(yamlText)
            } catch (Exception e) {
                String errMsg = "Configuration processing failed during YAML parsing: ${e.message}"
                telemetry.emitEvent("config", "parse_failed", [
                    correlationId: correlationId,
                    phase: "parsing",
                    error: e.message
                ])
                throw new RuntimeException(errMsg, e)
            }

            if (parsedConfig == null || parsedConfig.isEmpty()) {
                String errMsg = "YAML parsing produced an empty configuration. The provided YAML must contain project metadata."
                audit.emitAuditEvent("CONFIG_PROCESS_FAILED", errMsg, correlationId)
                telemetry.emitEvent("config", "process_failed", [
                    correlationId: correlationId,
                    phase: "parsing",
                    error: errMsg
                ])
                throw new IllegalArgumentException(errMsg)
            }

            /*
             * Phase 2: Validate against project schema
             */
            LoggingUtils.info("ProjectConfigurationManager",
                "Phase 2: Validating schema [correlationId=${correlationId}]")
            Boolean schemaValid
            try {
                schemaValid = schemaValidator.validate(parsedConfig)
            } catch (Exception e) {
                String errMsg = "Configuration processing failed during schema validation: ${e.message}"
                telemetry.emitEvent("config", "validation_failed", [
                    correlationId: correlationId,
                    phase: "schema_validation",
                    error: e.message
                ])
                throw new RuntimeException(errMsg, e)
            }

            if (!schemaValid) {
                List<String> errors = schemaValidator.getValidationErrors()
                String errMsg = "Configuration schema validation failed with ${errors.size()} error(s): ${errors.join('; ')}"
                LoggingUtils.error("ProjectConfigurationManager", errMsg, null)
                audit.emitAuditEvent("CONFIG_VALIDATION_FAILED", errMsg, correlationId)
                telemetry.emitEvent("config", "validation_failed", [
                    correlationId: correlationId,
                    phase: "schema_validation",
                    errorCount: errors.size(),
                    errors: errors
                ])
                throw new IllegalArgumentException(errMsg)
            }

            List<String> warnings = schemaValidator.getValidationWarnings()
            if (!warnings.isEmpty()) {
                LoggingUtils.warn("ProjectConfigurationManager",
                    "Configuration validation produced ${warnings.size()} warning(s): ${warnings.join('; ')} [correlationId=${correlationId}]")
            }

            /*
             * Phase 3: Resolve configuration inheritance
             */
            LoggingUtils.info("ProjectConfigurationManager",
                "Phase 3: Resolving inheritance [correlationId=${correlationId}]")
            Map resolved
            try {
                resolved = inheritanceManager.resolve(parsedConfig)
            } catch (Exception e) {
                String errMsg = "Configuration processing failed during inheritance resolution: ${e.message}"
                telemetry.emitEvent("config", "inheritance_failed", [
                    correlationId: correlationId,
                    phase: "inheritance",
                    error: e.message
                ])
                throw new RuntimeException(errMsg, e)
            }

            if (resolved == null || resolved.isEmpty()) {
                String errMsg = "Configuration inheritance resolution produced an empty result."
                audit.emitAuditEvent("CONFIG_PROCESS_FAILED", errMsg, correlationId)
                telemetry.emitEvent("config", "process_failed", [
                    correlationId: correlationId,
                    phase: "inheritance",
                    error: errMsg
                ])
                throw new RuntimeException(errMsg)
            }

            /*
             * Phase 4: Finalize and cache resolved configuration
             */
            this.resolvedConfig = resolved
            this.initialized = true

            long endTime = System.currentTimeMillis()
            long duration = endTime - startTime
            int topLevelKeys = resolved.size()
            int metaKeys = resolved["_meta"] instanceof Map ? ((Map) resolved["_meta"]).size() : 0

            LoggingUtils.info("ProjectConfigurationManager",
                "Configuration processing completed in ${duration}ms: ${topLevelKeys} top-level keys, ${metaKeys} metadata entries [correlationId=${correlationId}]")

            audit.emitAuditEvent("CONFIG_PROCESS_COMPLETED",
                "Project configuration processed successfully in ${duration}ms", correlationId)
            telemetry.emitEvent("config", "completed", [
                correlationId: correlationId,
                durationMs: duration,
                topLevelKeys: topLevelKeys,
                resolvedProfile: extractProfile(resolved)
            ])

            return resolved

        } catch (IllegalArgumentException e) {
            LoggingUtils.error("ProjectConfigurationManager",
                "Configuration processing rejected: ${e.message}", e)
            throw e

        } catch (RuntimeException e) {
            LoggingUtils.error("ProjectConfigurationManager",
                "Configuration processing failed: ${e.message}", e)
            audit.emitAuditEvent("CONFIG_PROCESS_ERROR",
                "Configuration processing error: ${e.message}", correlationId)
            telemetry.emitEvent("config", "error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            throw e

        } catch (Exception e) {
            String errMsg = "Unexpected configuration processing failure: ${e.message}"
            LoggingUtils.error("ProjectConfigurationManager", errMsg, e)
            audit.emitAuditEvent("CONFIG_PROCESS_UNEXPECTED_ERROR", errMsg, correlationId)
            telemetry.emitEvent("config", "unexpected_error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    Map getResolvedConfig() {
        if (!initialized || this.resolvedConfig == null) {
            throw new IllegalStateException(
                "Configuration has not been processed yet. Call process(yamlText) before accessing resolved configuration.")
        }
        return deepCloneMap(this.resolvedConfig)
    }

    Boolean isInitialized() {
        return this.initialized
    }

    String getProjectName() {
        if (this.resolvedConfig == null) return null
        Map project = extractMapSafely(this.resolvedConfig, "project")
        if (project == null) return null
        Map metadata = extractMapSafely(project, "metadata")
        if (metadata == null) return null
        Object name = metadata.get("projectName")
        return name instanceof String ? (String) name : null
    }

    String getBusinessUnit() {
        if (this.resolvedConfig == null) return null
        Map project = extractMapSafely(this.resolvedConfig, "project")
        if (project == null) return null
        Map metadata = extractMapSafely(project, "metadata")
        if (metadata == null) return null
        Object bu = metadata.get("businessUnit")
        return bu instanceof String ? (String) bu : null
    }

    String getTeam() {
        if (this.resolvedConfig == null) return null
        Map project = extractMapSafely(this.resolvedConfig, "project")
        if (project == null) return null
        Map metadata = extractMapSafely(project, "metadata")
        if (metadata == null) return null
        Object team = metadata.get("team")
        return team instanceof String ? (String) team : null
    }

    String getCorrelationId() {
        return this.correlationId
    }

    Map getValidationErrors() {
        if (!this.initialized) return [:]
        return [
            errors: schemaValidator.getValidationErrors(),
            warnings: schemaValidator.getValidationWarnings()
        ]
    }

    /*
     * Private helpers
     */

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
    private String extractProfile(Map config) {
        Map project = extractMapSafely(config, "project")
        if (project != null) {
            Map meta = extractMapSafely(config, "_meta")
            if (meta != null) {
                Object profile = meta.get("inheritanceProfile")
                if (profile instanceof String) return (String) profile
            }
        }
        return "default"
    }
}
