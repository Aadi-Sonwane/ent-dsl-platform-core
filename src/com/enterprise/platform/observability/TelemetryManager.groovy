package com.enterprise.platform.observability

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class TelemetryManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String DEFAULT_SIEM_ENDPOINT = ""
    private static final int MAX_EVENT_SIZE_CHARS = 32000

    private final Object steps
    private final String instanceId
    private final String environmentName

    TelemetryManager(Object steps) {
        this.steps = steps
        this.instanceId = resolveInstanceId()
        this.environmentName = resolveEnvironmentName()
    }

    String generateCorrelationId() {
        String uuid = java.util.UUID.randomUUID().toString()
        return "buildos-${uuid}"
    }

    void emitEvent(String domain, String action, Map<String, Object> properties) {
        if (properties == null) properties = [:]

        long timestamp = currentTimeMillis()
        Map<String, Object> event = buildEvent(domain, action, properties, timestamp)
        String json = serializeToJson(event)

        if (json.length() > MAX_EVENT_SIZE_CHARS) {
            Map<String, Object> truncated = new HashMap<>(event)
            truncated["_truncated"] = true
            truncated["_originalSize"] = json.length()
            truncated.remove("properties")
            Map<String, Object> safeProps = new HashMap<>()
            int count = 0
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (count >= 20) break
                safeProps.put(entry.getKey(), entry.getValue()?.toString()?.take(200))
                count++
            }
            truncated["properties"] = safeProps
            json = serializeToJson(truncated)
        }

        LoggingUtils.info("TelemetryManager",
            "Event emitted: ${domain}.${action} [correlationId=${event.correlationId}]")

        try {
            steps.echo(json)
        } catch (Exception e) {
            /* Echo fallback */
        }

        emitToSiemEndpoint(json, domain, action)
    }

    void emitLatencyMetric(String metricName, long durationMs, Map<String, String> tags) {
        if (!ValidationUtils.isNonEmpty(metricName)) return
        if (tags == null) tags = [:]

        Map<String, Object> metricEvent = [:]
        metricEvent["type"] = "latency_metric"
        metricEvent["metric"] = metricName
        metricEvent["valueMs"] = durationMs
        metricEvent["tags"] = tags
        metricEvent["timestamp"] = formatTimestamp()

        emitEvent("metrics", metricName, metricEvent)
    }

    void emitAuditEvent(String action, String outcome, String resourceType, String resourceId,
                        String actor, String correlationId) {
        Map<String, Object> auditEvent = [:]
        auditEvent["action"] = action
        auditEvent["outcome"] = outcome
        auditEvent["resourceType"] = resourceType
        auditEvent["resourceId"] = resourceId
        auditEvent["actor"] = actor != null ? actor : "jenkins-pipeline"
        auditEvent["correlationId"] = correlationId

        emitEvent("audit", action, auditEvent)
    }

    Map<String, Object> buildEvent(String domain, String action,
                                   Map<String, Object> properties, long timestamp) {
        Map<String, Object> event = [:]
        event["eventId"] = java.util.UUID.randomUUID().toString()
        event["timestamp"] = timestamp
        event["timestampISO"] = formatTimestampISO(timestamp)
        event["domain"] = domain
        event["action"] = action
        event["source"] = "buildos-platform"
        event["instanceId"] = instanceId
        event["environment"] = environmentName
        event["correlationId"] = properties.containsKey("correlationId") ?
            properties.get("correlationId").toString() : generateCorrelationId()

        if (properties.containsKey("correlationId")) {
            Map<String, Object> propsWithoutCorrelationId = new HashMap<>(properties)
            propsWithoutCorrelationId.remove("correlationId")
            event["properties"] = propsWithoutCorrelationId
        } else {
            event["properties"] = new HashMap<>(properties)
        }

        return event
    }

    String getInstanceId() {
        return this.instanceId
    }

    String getEnvironmentName() {
        return this.environmentName
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String resolveInstanceId() {
        try {
            return steps.env.BUILD_TAG ?: steps.env.JOB_NAME ?: "unknown"
        } catch (Exception e) {
            return "unknown"
        }
    }

    @NonCPS
    private String resolveEnvironmentName() {
        try {
            return steps.env.BUILDOS_ENV ?: "production"
        } catch (Exception e) {
            return "production"
        }
    }

    @NonCPS
    private long currentTimeMillis() {
        return System.currentTimeMillis()
    }

    @NonCPS
    private String formatTimestamp() {
        try {
            return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }

    @NonCPS
    private String formatTimestampISO(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }

    @NonCPS
    private String serializeToJson(Map<String, Object> data) {
        return manualMapToJson(data)
    }

    @NonCPS
    private String manualMapToJson(Map<String, Object> map) {
        if (map == null) return "{}"
        StringBuilder sb = new StringBuilder("{")
        boolean first = true
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!first) sb.append(",")
            first = false
            String key = entry.getKey() != null ? entry.getKey().toString() : "null"
            sb.append("\"").append(escapeJson(key)).append("\":")
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
            Map<String, Object> asMap = (Map<String, Object>) value
            return manualMapToJson(asMap)
        }
        if (value instanceof List) {
            List list = (List) value
            StringBuilder sb = new StringBuilder("[")
            boolean first = true
            for (Object item : list) {
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
            .replace("\b", "\\b")
            .replace("\f", "\\f")
    }

    private void emitToSiemEndpoint(String jsonEvent, String domain, String action) {
        if (!ValidationUtils.isNonEmpty(DEFAULT_SIEM_ENDPOINT)) return
        try {
            steps.httpRequest(
                url: DEFAULT_SIEM_ENDPOINT,
                httpMode: "POST",
                contentType: "APPLICATION_JSON",
                requestBody: jsonEvent,
                validResponseCodes: "200,201,202,204",
                quiet: true,
                wrapAsMultipart: false
            )
        } catch (Exception e) {
            LoggingUtils.warn("TelemetryManager",
                "Failed to emit event to SIEM endpoint: ${e.message}")
        }
    }
}
