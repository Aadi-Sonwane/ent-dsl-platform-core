package com.enterprise.platform.nexus

import com.enterprise.platform.framework.RetryFramework
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class CleanupPolicyManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String CLEANUP_POLICY_API_PATH = "/service/rest/v1/cleanup-policies"
    private static final int MAX_POLICY_NAME_LENGTH = 128

    private final Object steps
    private final String nexusUrl
    private final String credentialsId
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final RetryFramework retry
    private final String correlationId

    CleanupPolicyManager(Object steps, String nexusUrl, String credentialsId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    CleanupPolicyManager(Object steps, String nexusUrl, String credentialsId, String correlationId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = correlationId
    }

    Map createCleanupPolicy(String name, Map config) {
        LoggingUtils.info("CleanupPolicyManager",
            "Creating cleanup policy '${name}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(name)) {
            String errMsg = "Cleanup policy name must not be null or empty"
            audit.emitAuditEvent("CLEANUP_POLICY_CREATE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("nexus.cleanup", "create_failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        if (name.length() > MAX_POLICY_NAME_LENGTH) {
            String errMsg = "Cleanup policy name '${name}' exceeds maximum length of ${MAX_POLICY_NAME_LENGTH}"
            audit.emitAuditEvent("CLEANUP_POLICY_CREATE_FAILED", errMsg, correlationId)
            throw new IllegalArgumentException(errMsg)
        }

        if (config == null) {
            config = [:]
        }

        String policyUrl = "${this.nexusUrl}${CLEANUP_POLICY_API_PATH}"

        try {
            Map requestBody = buildPolicyRequestBody(name, config)
            String jsonPayload = convertToJson(requestBody)

            Map response = retry.retry("CreateCleanupPolicy_${name}", {
                def httpResponse = steps.httpRequest(
                    url: policyUrl,
                    httpMode: "POST",
                    contentType: "APPLICATION_JSON",
                    requestBody: jsonPayload,
                    customHeaders: buildAuthHeaders(),
                    validResponseCodes: "200:599",
                    quiet: true,
                    wrapAsMultipart: false
                )
                return [
                    status: httpResponse.status,
                    content: httpResponse.content
                ]
            }, [
                maxRetries: 3,
                backoffBaseMs: 1000,
                backoffMultiplier: 2.0
            ])

            int statusCode = response.status instanceof Integer ?
                (Integer) response.status : Integer.parseInt(response.status.toString())

            if (statusCode == 200 || statusCode == 201) {
                LoggingUtils.info("CleanupPolicyManager",
                    "Cleanup policy '${name}' created successfully [correlationId=${correlationId}]")
                audit.emitAuditEvent("CLEANUP_POLICY_CREATED",
                    "Cleanup policy '${name}' created", correlationId)
                telemetry.emitEvent("nexus.cleanup", "created", [
                    correlationId: correlationId,
                    policyName: name,
                    format: requestBody.format ?: "all"
                ])
                return [name: name, status: "CREATED"]

            } else if (statusCode == 400) {
                String responseBody = response.content != null ? response.content.toString() : ""
                if (responseBody.contains("already exists")) {
                    LoggingUtils.info("CleanupPolicyManager",
                        "Cleanup policy '${name}' already exists (idempotent) [correlationId=${correlationId}]")
                    audit.emitAuditEvent("CLEANUP_POLICY_ALREADY_EXISTS",
                        "Cleanup policy '${name}' already exists, proceeding", correlationId)
                    telemetry.emitEvent("nexus.cleanup", "already_exists", [
                        correlationId: correlationId,
                        policyName: name
                    ])
                    return updateCleanupPolicy(name, config)
                }
                String errMsg = "Failed to create cleanup policy '${name}': HTTP ${statusCode} - ${responseBody}"
                LoggingUtils.error("CleanupPolicyManager", errMsg, null)
                audit.emitAuditEvent("CLEANUP_POLICY_CREATE_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)

            } else if (statusCode == 401 || statusCode == 403) {
                String errMsg = "Authentication failed for Nexus cleanup policy API. Verify credentials."
                audit.emitAuditEvent("CLEANUP_POLICY_AUTH_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)

            } else {
                String responseBody = response.content != null ? response.content.toString() : ""
                String errMsg = "Unexpected HTTP ${statusCode} creating cleanup policy '${name}': ${responseBody}"
                LoggingUtils.error("CleanupPolicyManager", errMsg, null)
                audit.emitAuditEvent("CLEANUP_POLICY_CREATE_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)
            }

        } catch (RuntimeException e) {
            if (e.message != null && (e.message.contains("already exists") || e.message.contains("idempotent"))) {
                throw e
            }
            LoggingUtils.error("CleanupPolicyManager",
                "Cleanup policy creation failed for '${name}': ${e.message}", e)
            audit.emitAuditEvent("CLEANUP_POLICY_CREATE_ERROR",
                "Error creating cleanup policy '${name}': ${e.message}", correlationId)
            throw e

        } catch (Exception e) {
            String errMsg = "Unexpected error creating cleanup policy '${name}': ${e.message}"
            LoggingUtils.error("CleanupPolicyManager", errMsg, e)
            audit.emitAuditEvent("CLEANUP_POLICY_CREATE_ERROR", errMsg, correlationId)
            throw new RuntimeException(errMsg, e)
        }
    }

    private Map updateCleanupPolicy(String name, Map config) {
        LoggingUtils.info("CleanupPolicyManager",
            "Updating existing cleanup policy '${name}' [correlationId=${correlationId}]")

        String policyUrl = "${this.nexusUrl}${CLEANUP_POLICY_API_PATH}/${urlEncode(name)}"

        try {
            Map requestBody = buildPolicyRequestBody(name, config)
            String jsonPayload = convertToJson(requestBody)

            Map response = retry.retry("UpdateCleanupPolicy_${name}", {
                def httpResponse = steps.httpRequest(
                    url: policyUrl,
                    httpMode: "PUT",
                    contentType: "APPLICATION_JSON",
                    requestBody: jsonPayload,
                    customHeaders: buildAuthHeaders(),
                    validResponseCodes: "200:599",
                    quiet: true,
                    wrapAsMultipart: false
                )
                return [
                    status: httpResponse.status,
                    content: httpResponse.content
                ]
            }, [
                maxRetries: 2,
                backoffBaseMs: 500
            ])

            int statusCode = response.status instanceof Integer ?
                (Integer) response.status : Integer.parseInt(response.status.toString())

            if (statusCode == 200 || statusCode == 204) {
                LoggingUtils.info("CleanupPolicyManager",
                    "Cleanup policy '${name}' updated successfully [correlationId=${correlationId}]")
                audit.emitAuditEvent("CLEANUP_POLICY_UPDATED",
                    "Cleanup policy '${name}' updated", correlationId)
                return [name: name, status: "UPDATED"]
            }

            String responseBody = response.content != null ? response.content.toString() : ""
            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to update cleanup policy '${name}': HTTP ${statusCode} - ${responseBody} [correlationId=${correlationId}]")
            return [name: name, status: "UPDATE_FAILED"]

        } catch (Exception e) {
            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to update cleanup policy '${name}': ${e.message} [correlationId=${correlationId}]")
            return [name: name, status: "UPDATE_FAILED"]
        }
    }

    Boolean cleanupPolicyExists(String name) {
        if (!ValidationUtils.isNonEmpty(name)) return false
        String listUrl = "${this.nexusUrl}${CLEANUP_POLICY_API_PATH}"

        try {
            Map response = retry.retry("ListCleanupPolicies", {
                def httpResponse = steps.httpRequest(
                    url: listUrl,
                    httpMode: "GET",
                    contentType: "APPLICATION_JSON",
                    customHeaders: buildAuthHeaders(),
                    validResponseCodes: "200:599",
                    quiet: true,
                    wrapAsMultipart: false
                )
                return [
                    status: httpResponse.status,
                    content: httpResponse.content
                ]
            }, [
                maxRetries: 2,
                backoffBaseMs: 500
            ])

            int statusCode = response.status instanceof Integer ?
                (Integer) response.status : Integer.parseInt(response.status.toString())

            if (statusCode == 200) {
                String content = response.content != null ? response.content.toString() : "[]"
                List<Map> policies = parseJsonArray(content)
                for (Map policy : policies) {
                    String policyName = policy.name instanceof String ? policy.name.toString() : ""
                    if (policyName.equals(name)) return true
                }
            }
            return false

        } catch (Exception e) {
            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to check cleanup policy '${name}': ${e.message}")
            return false
        }
    }

    Map deleteCleanupPolicy(String name) {
        LoggingUtils.info("CleanupPolicyManager",
            "Deleting cleanup policy '${name}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(name)) {
            return [name: name, status: "SKIPPED"]
        }

        String deleteUrl = "${this.nexusUrl}${CLEANUP_POLICY_API_PATH}/${urlEncode(name)}"

        try {
            Map response = retry.retry("DeleteCleanupPolicy_${name}", {
                def httpResponse = steps.httpRequest(
                    url: deleteUrl,
                    httpMode: "DELETE",
                    contentType: "APPLICATION_JSON",
                    customHeaders: buildAuthHeaders(),
                    validResponseCodes: "200:599",
                    quiet: true,
                    wrapAsMultipart: false
                )
                return [
                    status: httpResponse.status,
                    content: httpResponse.content
                ]
            }, [
                maxRetries: 2,
                backoffBaseMs: 500
            ])

            int statusCode = response.status instanceof Integer ?
                (Integer) response.status : Integer.parseInt(response.status.toString())

            if (statusCode == 200 || statusCode == 204) {
                audit.emitAuditEvent("CLEANUP_POLICY_DELETED",
                    "Cleanup policy '${name}' deleted", correlationId)
                return [name: name, status: "DELETED"]
            }
            if (statusCode == 404) {
                LoggingUtils.info("CleanupPolicyManager",
                    "Cleanup policy '${name}' not found for deletion (idempotent) [correlationId=${correlationId}]")
                return [name: name, status: "NOT_FOUND"]
            }

            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to delete cleanup policy '${name}': HTTP ${statusCode}")
            return [name: name, status: "DELETE_FAILED"]

        } catch (Exception e) {
            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to delete cleanup policy '${name}': ${e.message}")
            return [name: name, status: "DELETE_FAILED"]
        }
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map buildPolicyRequestBody(String name, Map config) {
        Map body = [:]
        body["name"] = name
        body["notes"] = config.notes instanceof String ? config.notes.toString() :
            "Managed by BuildOS Platform CleanupPolicyManager"

        String format = config.format instanceof String ? config.format.toString() : null
        if (ValidationUtils.isNonEmpty(format)) {
            body["format"] = format
        }

        Map criteria = [:]

        Object lastDownloadRaw = config.lastDownloadedBeforeDays
        if (lastDownloadedBeforeDays instanceof Number) {
            criteria["lastDownloaded"] = ((Number) lastDownloadedBeforeDays).intValue()
        }

        Object lastBlobUpdatedRaw = config.lastBlobUpdatedBeforeDays
        if (lastBlobUpdatedRaw instanceof Number) {
            criteria["lastBlobUpdated"] = ((Number) lastBlobUpdatedRaw).intValue()
        }

        Object retainRaw = config.retain
        if (retainRaw instanceof Number) {
            criteria["retain"] = ((Number) retainRaw).intValue()
        }

        Object regexRaw = config.assetRegex
        if (regexRaw instanceof String && ValidationUtils.isNonEmpty(regexRaw.toString())) {
            criteria["regex"] = regexRaw.toString()
        }

        if (!criteria.isEmpty()) {
            body["criteria"] = criteria
        }

        return body
    }

    private List<Map<String, Object>> buildAuthHeaders() {
        return [
            [name: "Authorization", value: "\${NEXUS_AUTH_HEADER}"]
        ]
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
    private String urlEncode(String value) {
        if (value == null) return ""
        try {
            return java.net.URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
                .replace("%2F", "/")
        } catch (Exception e) {
            return value
        }
    }

    @NonCPS
    private String convertToJson(Map data) {
        if (data == null) return "{}"
        return serializeMapToJson(data)
    }

    @NonCPS
    private String serializeMapToJson(Map map) {
        StringBuilder sb = new StringBuilder("{")
        boolean first = true
        for (Map.Entry entry : map.entrySet()) {
            if (!first) sb.append(",")
            first = false
            String key = entry.key != null ? entry.key.toString() : "null"
            sb.append("\"").append(escapeJson(key)).append("\":")
            sb.append(serializeJsonValue(entry.value))
        }
        sb.append("}")
        return sb.toString()
    }

    @NonCPS
    private String serializeJsonValue(Object value) {
        if (value == null) return "null"
        if (value instanceof Number) return value.toString()
        if (value instanceof Boolean) return value.toString()
        if (value instanceof Map) return serializeMapToJson((Map) value)
        if (value instanceof List) return serializeListToJson((List) value)
        return "\"" + escapeJson(value.toString()) + "\""
    }

    @NonCPS
    private String serializeListToJson(List list) {
        StringBuilder sb = new StringBuilder("[")
        boolean first = true
        for (Object item : list) {
            if (!first) sb.append(",")
            first = false
            sb.append(serializeJsonValue(item))
        }
        sb.append("]")
        return sb.toString()
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
    private List<Map> parseJsonArray(String json) {
        List<Map> result = []
        if (json == null || json.trim().isEmpty()) return result
        String trimmed = json.trim()
        if (!trimmed.startsWith("[")) return result
        try {
            def parsed = new groovy.json.JsonSlurper().parseText(trimmed)
            if (parsed instanceof List) {
                for (Object item : (List) parsed) {
                    if (item instanceof Map) {
                        result.add((Map) item)
                    }
                }
            }
        } catch (Exception e) {
            LoggingUtils.warn("CleanupPolicyManager",
                "Failed to parse cleanup policy JSON response: ${e.message}")
        }
        return result
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
