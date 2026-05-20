package com.enterprise.platform.observability

import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class OpenTelemetryManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String DEFAULT_OTEL_ENDPOINT = ""
    private static final String TRACE_STATE_HEADER = "traceparent"
    private static final String DEFAULT_SERVICE_NAME = "buildos-platform"

    private final Object steps
    private final TelemetryManager telemetry
    private final String correlationId
    private final Map<String, Object> activeSpans = [:]
    private final List<Map<String, Object>> completedSpans = []

    private String otelEndpoint
    private String serviceName
    private String traceId

    OpenTelemetryManager(Object steps) {
        this.steps = steps
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
        this.otelEndpoint = DEFAULT_OTEL_ENDPOINT
        this.serviceName = DEFAULT_SERVICE_NAME
        this.traceId = generateTraceId()
    }

    OpenTelemetryManager(Object steps, String correlationId) {
        this.steps = steps
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
        this.otelEndpoint = DEFAULT_OTEL_ENDPOINT
        this.serviceName = DEFAULT_SERVICE_NAME
        this.traceId = generateTraceId()
    }

    String startSpan(String spanName, Map<String, String> attributes) {
        if (!ValidationUtils.isNonEmpty(spanName)) {
            spanName = "unnamed-span"
        }
        if (attributes == null) attributes = [:]

        String spanId = generateSpanId()
        long startTime = currentTimeMillisNanos()

        Map<String, Object> span = [:]
        span["spanId"] = spanId
        span["traceId"] = traceId
        span["name"] = spanName
        span["kind"] = "INTERNAL"
        span["startTimeUnixNano"] = startTime
        span["startTimeISO"] = formatTimestamp(startTime / 1000000)
        span["attributes"] = new HashMap<>(attributes)
        span["status"] = "UNSET"
        span["parentSpanId"] = findParentSpanId()

        activeSpans[spanId] = span

        LoggingUtils.info("OpenTelemetryManager",
            "Span started: '${spanName}' [spanId=${spanId}, traceId=${traceId}]")

        return spanId
    }

    void endSpan(String spanId, String status, Map<String, Object> additionalAttributes) {
        if (!ValidationUtils.isNonEmpty(spanId)) return
        if (status == null) status = "OK"

        Map<String, Object> span = activeSpans.get(spanId) as Map<String, Object>
        if (span == null) {
            LoggingUtils.warn("OpenTelemetryManager",
                "Cannot end span '${spanId}': not found in active spans")
            return
        }

        long endTime = currentTimeMillisNanos()
        span["endTimeUnixNano"] = endTime
        span["endTimeISO"] = formatTimestamp(endTime / 1000000)

        if ("OK".equals(status) || "UNSET".equals(status)) {
            span["status"] = [code: "OK"]
        } else if ("ERROR".equals(status)) {
            span["status"] = [code: "ERROR", message: "Span completed with error"]
        } else {
            span["status"] = [code: status]
        }

        if (additionalAttributes != null && !additionalAttributes.isEmpty()) {
            Map<String, Object> existingAttrs = span["attributes"] instanceof Map ?
                (Map<String, Object>) span["attributes"] : [:]
            existingAttrs.putAll(additionalAttributes)
            span["attributes"] = existingAttrs
        }

        if (span["startTimeUnixNano"] instanceof Number) {
            long start = ((Number) span["startTimeUnixNano"]).longValue()
            span["durationNanos"] = endTime - start
            span["durationMs"] = (endTime - start) / 1000000
        }

        activeSpans.remove(spanId)
        completedSpans.add(span)

        LoggingUtils.info("OpenTelemetryManager",
            "Span ended: '${span.name}' (${status}) [spanId=${spanId}, durationMs=${span["durationMs"]}]")

        telemetry.emitLatencyMetric("span.${span.name}.duration",
            span["durationMs"] instanceof Number ? ((Number) span["durationMs"]).longValue() : 0,
            [spanId: spanId, traceId: traceId, status: status])
    }

    void endSpanWithError(String spanId, String errorMessage, Throwable error) {
        Map<String, Object> attrs = [:]
        if (ValidationUtils.isNonEmpty(errorMessage)) {
            attrs["error.message"] = errorMessage
        }
        if (error != null) {
            attrs["error.type"] = error.getClass().getName()
        }
        endSpan(spanId, "ERROR", attrs)
    }

    void injectTraceContext() {
        String traceParent = buildTraceParentHeader()
        try {
            steps.env.put("TRACEPARENT", traceParent)
            steps.env.put("TRACE_ID", traceId)
        } catch (Exception e) {
            LoggingUtils.warn("OpenTelemetryManager",
                "Failed to inject trace context into environment: ${e.message}")
        }
    }

    String getTraceId() {
        return this.traceId
    }

    String getCurrentSpanId() {
        try {
            return activeSpans.keySet().iterator().next()
        } catch (Exception e) {
            return ""
        }
    }

    List<Map<String, Object>> exportCompletedSpans() {
        return new ArrayList<>(completedSpans)
    }

    Map<String, Object> exportTrace() {
        Map<String, Object> traceExport = [:]
        traceExport["traceId"] = traceId
        traceExport["serviceName"] = serviceName
        traceExport["correlationId"] = correlationId
        traceExport["resourceSpans"] = buildResourceSpans()
        traceExport["completedSpans"] = completedSpans.size()
        traceExport["activeSpans"] = activeSpans.size()

        return traceExport
    }

    void flushToEndpoint() {
        if (!ValidationUtils.isNonEmpty(otelEndpoint)) return

        try {
            Map<String, Object> tracePayload = buildOtlpPayload()
            String json = manualMapToJson(tracePayload)

            steps.httpRequest(
                url: otelEndpoint,
                httpMode: "POST",
                contentType: "application/json",
                requestBody: json,
                customHeaders: [[name: "Content-Type", value: "application/json"]],
                validResponseCodes: "200,201,202,204",
                quiet: true,
                wrapAsMultipart: false
            )

            LoggingUtils.info("OpenTelemetryManager",
                "Trace exported to OTLP endpoint: ${completedSpans.size()} spans [correlationId=${correlationId}]")

        } catch (Exception e) {
            LoggingUtils.warn("OpenTelemetryManager",
                "Failed to export trace to OTLP endpoint: ${e.message}")
        }
    }

    void setOtelEndpoint(String endpoint) {
        if (ValidationUtils.isNonEmpty(endpoint)) {
            this.otelEndpoint = endpoint
        }
    }

    void setServiceName(String name) {
        if (ValidationUtils.isNonEmpty(name)) {
            this.serviceName = name
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String generateTraceId() {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < 32; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)))
        }
        return sb.toString()
    }

    @NonCPS
    private String generateSpanId() {
        StringBuilder sb = new StringBuilder()
        for (int i = 0; i < 16; i++) {
            sb.append(Integer.toHexString((int) (Math.random() * 16)))
        }
        return sb.toString()
    }

    @NonCPS
    private String findParentSpanId() {
        try {
            if (!activeSpans.isEmpty()) {
                return activeSpans.keySet().iterator().next()
            }
        } catch (Exception e) { }
        return ""
    }

    @NonCPS
    private String buildTraceParentHeader() {
        String version = "00"
        String spanId = getCurrentSpanId()
        if (!ValidationUtils.isNonEmpty(spanId)) {
            spanId = generateSpanId()
        }
        String traceFlags = "01"
        return "${version}-${traceId}-${spanId}-${traceFlags}"
    }

    @NonCPS
    private long currentTimeMillisNanos() {
        return System.currentTimeMillis() * 1000000 + (int) (Math.random() * 1000000)
    }

    @NonCPS
    private String formatTimestamp(long epochMs) {
        try {
            return new Date(epochMs).format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }

    @NonCPS
    private List<Map<String, Object>> buildResourceSpans() {
        List<Map<String, Object>> resourceSpansList = []

        Map<String, Object> resource = [:]
        resource["attributes"] = [
            [key: "service.name", value: [stringValue: serviceName]],
            [key: "telemetry.sdk.name", value: [stringValue: "buildos-platform"]],
            [key: "telemetry.sdk.language", value: [stringValue: "groovy"]],
            [key: "telemetry.sdk.version", value: [stringValue: "1.0.0"]]
        ]

        List<Map<String, Object>> scopeSpans = []
        for (Map<String, Object> span : completedSpans) {
            scopeSpans.add([
                spanId: span["spanId"],
                traceId: span["traceId"],
                name: span["name"],
                kind: 1,
                startTimeUnixNano: span["startTimeUnixNano"],
                endTimeUnixNano: span["endTimeUnixNano"],
                status: span["status"],
                attributes: convertAttributesToOtlp(
                    span["attributes"] instanceof Map ? (Map<String, Object>) span["attributes"] : [:])
            ])
        }

        Map<String, Object> scopeSpanEntry = [:]
        scopeSpanEntry["scope"] = [name: serviceName, version: "1.0.0"]
        scopeSpanEntry["spans"] = scopeSpans

        scopeSpans.add(scopeSpanEntry)

        Map<String, Object> resourceSpan = [:]
        resourceSpan["resource"] = resource
        resourceSpan["scopeSpans"] = scopeSpans

        resourceSpansList.add(resourceSpan)
        return resourceSpansList
    }

    @NonCPS
    private List<Map<String, Object>> convertAttributesToOtlp(Map<String, Object> attrs) {
        List<Map<String, Object>> result = []
        if (attrs == null) return result
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            result.add([
                key: entry.getKey(),
                value: [stringValue: entry.getValue()?.toString() ?: ""]
            ])
        }
        return result
    }

    @NonCPS
    private Map<String, Object> buildOtlpPayload() {
        Map<String, Object> payload = [:]
        payload["resourceSpans"] = buildResourceSpans()
        return payload
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
        if (value instanceof Map) return manualMapToJson((Map<String, Object>) value)
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
}
