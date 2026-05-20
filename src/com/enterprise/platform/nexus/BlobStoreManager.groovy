package com.enterprise.platform.nexus

import com.enterprise.platform.framework.RetryFramework
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class BlobStoreManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String BLOB_STORE_API_PATH = "/service/rest/v1/blobstores"
    private static final int MAX_BLOB_STORE_NAME_LENGTH = 64

    private final Object steps
    private final String nexusUrl
    private final String credentialsId
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final RetryFramework retry
    private final String correlationId

    BlobStoreManager(Object steps, String nexusUrl, String credentialsId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    BlobStoreManager(Object steps, String nexusUrl, String credentialsId, String correlationId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = correlationId
    }

    Map createBlobStore(String name, Map config) {
        LoggingUtils.info("BlobStoreManager",
            "Creating blob store '${name}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(name)) {
            String errMsg = "Blob store name must not be null or empty"
            audit.emitAuditEvent("BLOB_STORE_CREATE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("nexus.blobstore", "create_failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        if (name.length() > MAX_BLOB_STORE_NAME_LENGTH) {
            String errMsg = "Blob store name '${name}' exceeds maximum length of ${MAX_BLOB_STORE_NAME_LENGTH}"
            audit.emitAuditEvent("BLOB_STORE_CREATE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("nexus.blobstore", "create_failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        if (config == null) {
            config = [:]
        }

        String blobStoreUrl = "${this.nexusUrl}${BLOB_STORE_API_PATH}/file"

        try {
            String path = config.path instanceof String && ValidationUtils.isNonEmpty(config.path.toString())
                ? config.path.toString() : "${name}-data"

            Map requestBody = [
                name: name,
                path: path,
                softQuota: buildSoftQuota(config)
            ]

            String jsonPayload = convertToJson(requestBody)

            Map response = retry.retry("CreateBlobStore_${name}", {
                def httpResponse = steps.httpRequest(
                    url: blobStoreUrl,
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
                LoggingUtils.info("BlobStoreManager",
                    "Blob store '${name}' created successfully [correlationId=${correlationId}]")
                audit.emitAuditEvent("BLOB_STORE_CREATED",
                    "Blob store '${name}' created at path '${path}'", correlationId)
                telemetry.emitEvent("nexus.blobstore", "created", [
                    correlationId: correlationId,
                    blobStoreName: name,
                    path: path
                ])
                return [name: name, path: path, status: "CREATED"]

            } else if (statusCode == 400) {
                String responseBody = response.content != null ? response.content.toString() : ""
                if (responseBody.contains("already exists") || responseBody.contains("used")) {
                    LoggingUtils.info("BlobStoreManager",
                        "Blob store '${name}' already exists (idempotent) [correlationId=${correlationId}]")
                    audit.emitAuditEvent("BLOB_STORE_ALREADY_EXISTS",
                        "Blob store '${name}' already exists, proceeding (idempotent)", correlationId)
                    telemetry.emitEvent("nexus.blobstore", "already_exists", [
                        correlationId: correlationId,
                        blobStoreName: name
                    ])
                    return [name: name, path: path, status: "ALREADY_EXISTS"]
                }
                String errMsg = "Failed to create blob store '${name}': HTTP ${statusCode} - ${responseBody}"
                LoggingUtils.error("BlobStoreManager", errMsg, null)
                audit.emitAuditEvent("BLOB_STORE_CREATE_FAILED", errMsg, correlationId)
                telemetry.emitEvent("nexus.blobstore", "create_failed", [
                    correlationId: correlationId,
                    blobStoreName: name,
                    httpStatus: statusCode,
                    response: responseBody
                ])
                throw new RuntimeException(errMsg)

            } else if (statusCode == 401 || statusCode == 403) {
                String errMsg = "Authentication failed for Nexus blob store API. Verify credentials '${credentialsId}' have admin access."
                audit.emitAuditEvent("BLOB_STORE_AUTH_FAILED", errMsg, correlationId)
                telemetry.emitEvent("nexus.blobstore", "auth_failed", [
                    correlationId: correlationId,
                    httpStatus: statusCode
                ])
                throw new RuntimeException(errMsg)

            } else {
                String responseBody = response.content != null ? response.content.toString() : ""
                String errMsg = "Unexpected HTTP ${statusCode} creating blob store '${name}': ${responseBody}"
                LoggingUtils.error("BlobStoreManager", errMsg, null)
                audit.emitAuditEvent("BLOB_STORE_CREATE_FAILED", errMsg, correlationId)
                telemetry.emitEvent("nexus.blobstore", "create_failed", [
                    correlationId: correlationId,
                    blobStoreName: name,
                    httpStatus: statusCode
                ])
                throw new RuntimeException(errMsg)
            }

        } catch (RuntimeException e) {
            if (e.message.contains("already exists") || e.message.contains("idempotent")) {
                throw e
            }
            LoggingUtils.error("BlobStoreManager",
                "Blob store creation failed for '${name}': ${e.message}", e)
            audit.emitAuditEvent("BLOB_STORE_CREATE_ERROR",
                "Error creating blob store '${name}': ${e.message}", correlationId)
            throw e

        } catch (Exception e) {
            String errMsg = "Unexpected error creating blob store '${name}': ${e.message}"
            LoggingUtils.error("BlobStoreManager", errMsg, e)
            audit.emitAuditEvent("BLOB_STORE_CREATE_ERROR", errMsg, correlationId)
            telemetry.emitEvent("nexus.blobstore", "create_error", [
                correlationId: correlationId,
                blobStoreName: name,
                error: e.message,
                errorClass: e.getClass().name
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    Boolean blobStoreExists(String name) {
        LoggingUtils.info("BlobStoreManager",
            "Checking if blob store '${name}' exists [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(name)) {
            return false
        }

        try {
            List<Map> blobStores = listBlobStores()
            for (Map store : blobStores) {
                String storeName = store.name instanceof String ? store.name.toString() : ""
                if (storeName.equals(name)) {
                    return true
                }
            }
            return false

        } catch (Exception e) {
            LoggingUtils.warn("BlobStoreManager",
                "Failed to check if blob store '${name}' exists: ${e.message}")
            return false
        }
    }

    List<Map> listBlobStores() {
        String listUrl = "${this.nexusUrl}${BLOB_STORE_API_PATH}"

        try {
            Map response = retry.retry("ListBlobStores", {
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
                return parseJsonArray(content)
            }

            LoggingUtils.warn("BlobStoreManager",
                "Failed to list blob stores: HTTP ${statusCode} [correlationId=${correlationId}]")
            return []

        } catch (Exception e) {
            LoggingUtils.warn("BlobStoreManager",
                "Failed to list blob stores: ${e.message} [correlationId=${correlationId}]")
            return []
        }
    }

    /*
     * Private helpers
     */

    private List<Map<String, Object>> buildAuthHeaders() {
        return [
            [name: "Authorization", value: "\${NEXUS_AUTH_HEADER}"]
        ]
    }

    private Map buildSoftQuota(Map config) {
        Map softQuota = [:]
        Object quotaRaw = config.softQuota
        if (quotaRaw instanceof Map) {
            Map quota = (Map) quotaRaw
            Object typeRaw = quota.type
            Object limitRaw = quota.limit
            if (typeRaw instanceof String && limitRaw instanceof Number) {
                softQuota["type"] = typeRaw.toString()
                softQuota["limit"] = ((Number) limitRaw).longValue()
            }
        }
        return softQuota.isEmpty() ? null : softQuota
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
            LoggingUtils.warn("BlobStoreManager",
                "Failed to parse blob store JSON response: ${e.message}")
        }
        return result
    }

    @NonCPS
    String getNexusUrl() {
        return this.nexusUrl
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
