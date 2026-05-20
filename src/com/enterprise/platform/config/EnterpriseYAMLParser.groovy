package com.enterprise.platform.config

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

class EnterpriseYAMLParser implements Serializable {
    private static final long serialVersionUID = 1L

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    EnterpriseYAMLParser(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    EnterpriseYAMLParser(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map parse(String yamlText) {
        LoggingUtils.info("EnterpriseYAMLParser", "Parsing YAML configuration [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(yamlText)) {
            String errMsg = "YAML input must not be null or empty"
            audit.emitAuditEvent("YAML_PARSE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("yaml", "parse_failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        try {
            Yaml yaml = new Yaml()
            Map parsedResult = yaml.load(yamlText)

            if (parsedResult == null) {
                String errMsg = "YAML parsing returned null. The provided content may be empty or whitespace-only."
                audit.emitAuditEvent("YAML_PARSE_FAILED", errMsg, correlationId)
                telemetry.emitEvent("yaml", "parse_null", [
                    correlationId: correlationId
                ])
                throw new IllegalArgumentException(errMsg)
            }

            if (!(parsedResult instanceof Map)) {
                String errMsg = "YAML root must be a mapping structure, got ${parsedResult.getClass().simpleName}"
                audit.emitAuditEvent("YAML_PARSE_FAILED", errMsg, correlationId)
                telemetry.emitEvent("yaml", "parse_invalid_root", [
                    correlationId: correlationId,
                    actualType: parsedResult.getClass().name
                ])
                throw new IllegalArgumentException(errMsg)
            }

            Map normalized = normalizeKeys(parsedResult)
            int totalKeys = countKeys(normalized)

            LoggingUtils.info("EnterpriseYAMLParser",
                "YAML parsed and normalized successfully: ${totalKeys} top-level entries [correlationId=${correlationId}]")

            audit.emitAuditEvent("YAML_PARSE_SUCCESS",
                "YAML configuration parsed successfully with ${totalKeys} entries", correlationId)
            telemetry.emitEvent("yaml", "parse_success", [
                correlationId: correlationId,
                totalKeys: totalKeys,
                inputLength: yamlText.length()
            ])

            return normalized

        } catch (YAMLException e) {
            String errMsg = "YAML syntax error: ${e.message}"
            LoggingUtils.error("EnterpriseYAMLParser", errMsg, e)
            audit.emitAuditEvent("YAML_PARSE_SYNTAX_ERROR", errMsg, correlationId)
            telemetry.emitEvent("yaml", "parse_syntax_error", [
                correlationId: correlationId,
                error: e.message
            ])
            throw new IllegalArgumentException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected YAML parsing failure: ${e.message}"
            LoggingUtils.error("EnterpriseYAMLParser", errMsg, e)
            audit.emitAuditEvent("YAML_PARSE_UNEXPECTED_ERROR", errMsg, correlationId)
            telemetry.emitEvent("yaml", "parse_unexpected_error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            throw new IllegalArgumentException(errMsg, e)
        }
    }

    @NonCPS
    private Map normalizeKeys(Map input) {
        Map result = [:]
        if (input == null) return result
        for (Map.Entry entry : input.entrySet()) {
            String key = entry.key.toString()
            Object value = entry.value
            if (value instanceof Map) {
                result[key] = normalizeKeys((Map) value)
            } else if (value instanceof List) {
                result[key] = normalizeList((List) value)
            } else {
                result[key] = value
            }
        }
        return result
    }

    @NonCPS
    private List normalizeList(List input) {
        List result = []
        if (input == null) return result
        for (Object item : input) {
            if (item instanceof Map) {
                result.add(normalizeKeys((Map) item))
            } else if (item instanceof List) {
                result.add(normalizeList((List) item))
            } else {
                result.add(item)
            }
        }
        return result
    }

    @NonCPS
    private int countKeys(Map input) {
        int count = 0
        if (input == null) return count
        for (Map.Entry entry : input.entrySet()) {
            count++
            Object value = entry.value
            if (value instanceof Map) {
                count += countKeys((Map) value)
            }
        }
        return count
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
