package com.enterprise.platform.observability

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class AuditLoggingManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String AUDIT_NAMESPACE = "buildos-audit"

    private final Object steps
    private final TelemetryManager telemetry

    AuditLoggingManager(Object steps) {
        this.steps = steps
        this.telemetry = new TelemetryManager(steps)
    }

    AuditLoggingManager(Object steps, TelemetryManager telemetry) {
        this.steps = steps
        this.telemetry = telemetry != null ? telemetry : new TelemetryManager(steps)
    }

    void emitAuditEvent(String action, String message, String correlationId) {
        if (!ValidationUtils.isNonEmpty(action)) {
            action = "UNKNOWN_ACTION"
        }
        if (correlationId == null) {
            correlationId = telemetry.generateCorrelationId()
        }

        long timestamp = currentTimeMillis()
        Map<String, Object> auditRecord = buildAuditRecord(action, message, correlationId, timestamp)
        String json = serializeAuditRecord(auditRecord)

        LoggingUtils.info("AuditLoggingManager",
            "Audit event: ${action} [correlationId=${correlationId}]")

        try {
            steps.echo(json)
        } catch (Exception e) {
            /* echo fallback */
        }

        telemetry.emitEvent("audit", action, [
            correlationId: correlationId,
            message: maskSensitiveData(message),
            action: action
        ])
    }

    void emitResourceChange(String resourceType, String resourceId, String changeType,
                            String actor, String correlationId) {
        if (!ValidationUtils.isNonEmpty(resourceType)) resourceType = "unknown"
        if (!ValidationUtils.isNonEmpty(resourceId)) resourceId = "unknown"
        if (!ValidationUtils.isNonEmpty(changeType)) changeType = "modified"
        if (correlationId == null) correlationId = telemetry.generateCorrelationId()

        Map<String, Object> changeRecord = buildAuditRecord(
            "RESOURCE_${changeType.toUpperCase()}",
            "${changeType} ${resourceType}: ${resourceId}",
            correlationId,
            currentTimeMillis()
        )
        changeRecord["resourceType"] = resourceType
        changeRecord["resourceId"] = resourceId
        changeRecord["changeType"] = changeType
        changeRecord["actor"] = actor != null ? actor : "jenkins-pipeline"

        String json = serializeAuditRecord(changeRecord)
        LoggingUtils.info("AuditLoggingManager",
            "Resource change: ${changeType} ${resourceType} '${resourceId}' [correlationId=${correlationId}]")

        try {
            steps.echo(json)
        } catch (Exception e) { }

        telemetry.emitEvent("audit", "resource_${changeType}", [
            correlationId: correlationId,
            resourceType: resourceType,
            resourceId: resourceId,
            changeType: changeType,
            actor: changeRecord["actor"]
        ])
    }

    void emitFailureAudit(String action, String errorMessage, String correlationId) {
        emitAuditEvent("${action}_FAILED",
            "Action '${action}' failed: ${errorMessage}", correlationId)
    }

    void emitSuccessAudit(String action, String description, String correlationId) {
        emitAuditEvent("${action}_SUCCESS",
            description, correlationId)
    }

    String getCorrelationId() {
        return telemetry.generateCorrelationId()
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map<String, Object> buildAuditRecord(String action, String message,
                                                  String correlationId, long timestamp) {
        Map<String, Object> record = [:]
        record["@namespace"] = AUDIT_NAMESPACE
        record["eventId"] = java.util.UUID.randomUUID().toString()
        record["timestamp"] = timestamp
        record["timestampISO"] = formatTimestampISO(timestamp)
        record["action"] = action
        record["message"] = message
        record["correlationId"] = correlationId
        record["source"] = "buildos-platform"
        record["severity"] = determineSeverity(action)
        record["environment"] = resolveEnvironment()
        record["instanceId"] = resolveInstanceId()
        return record
    }

    @NonCPS
    private String serializeAuditRecord(Map<String, Object> record) {
        return manualMapToJson(record)
    }

    @NonCPS
    private String manualMapToJson(Map<String, Object> map) {
        if (map == null) return "{}"
        StringBuilder sb = new StringBuilder("{")
        boolean first = true
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",")
            first = false
            sb.append("\"").append(escapeJson(entry.getKey())).append("\":")
            sb.append(manualValueToJson(entry.getValue()))
        }
        sb.append("}")
        return sb.toString()
    }

    @NonCPS
    private String manualValueToJson(Object value) {
        if (value == null) return "null"
        if (value instanceof Number) return value.toString()
        if (value instanceof Boolean) return value.toString()
        if (value instanceof Map) {
            return manualMapToJson((Map<String, Object>) value)
        }
        if (value instanceof List) {
            StringBuilder sb = new StringBuilder("[")
            boolean first = true
            for (Object item : (List) value) {
                if (!first) sb.append(",")
                first = false
                sb.append(manualValueToJson(item))
            }
            sb.append("]")
            return sb.toString()
        }
        return "\"" + escapeJson(value.toString()) + "\""
    }

    @NonCPS
    private String escapeJson(String input) {
        if (input == null) return ""
        return input
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }

    @NonCPS
    private String determineSeverity(String action) {
        if (action == null) return "INFO"
        String upper = action.toUpperCase()
        if (upper.contains("FAILED") || upper.contains("ERROR") || upper.contains("VIOLATION")) {
            return "ERROR"
        }
        if (upper.contains("WARNING") || upper.contains("SKIPPED")) {
            return "WARNING"
        }
        return "INFO"
    }

    @NonCPS
    private String resolveEnvironment() {
        try {
            return steps.env.BUILDOS_ENV ?: "production"
        } catch (Exception e) {
            return "production"
        }
    }

    @NonCPS
    private String resolveInstanceId() {
        try {
            return steps.env.BUILD_TAG ?: steps.env.JOB_NAME ?: "unknown"
        } catch (Exception e) {
            return "unknown"
        }
    }

    @NonCPS
    private String maskSensitiveData(String message) {
        if (message == null) return ""
        return message
            .replaceAll("(?i)(password|passwd|secret|token|credential)[=:][^\\s,;}]+", "\$1:***")
            .replaceAll("(?i)(-----BEGIN[^\\-]+-----)[^\\-]+(-----END[^\\-]+-----)", "\$1...\$2")
    }

    @NonCPS
    private long currentTimeMillis() {
        return System.currentTimeMillis()
    }

    @NonCPS
    private String formatTimestampISO(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }
}
