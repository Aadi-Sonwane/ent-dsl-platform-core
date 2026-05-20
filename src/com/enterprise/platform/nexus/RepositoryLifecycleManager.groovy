package com.enterprise.platform.nexus

import com.enterprise.platform.framework.RetryFramework
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class RepositoryLifecycleManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String REPOSITORIES_API_PATH = "/service/rest/v1/repositories"
    private static final List<String> VALID_FORMATS = ["maven2", "npm", "pypi", "docker", "raw", "apt", "yum"]
    private static final List<String> VALID_TYPES = ["hosted", "proxy", "group"]

    private final Object steps
    private final String nexusUrl
    private final String credentialsId
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final RetryFramework retry
    private final String correlationId

    RepositoryLifecycleManager(Object steps, String nexusUrl, String credentialsId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    RepositoryLifecycleManager(Object steps, String nexusUrl, String credentialsId, String correlationId) {
        this.steps = steps
        this.nexusUrl = normalizeUrl(nexusUrl)
        this.credentialsId = credentialsId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.retry = new RetryFramework(steps)
        this.correlationId = correlationId
    }

    Map createRepository(Map repoSpec) {
        if (repoSpec == null) {
            throw new IllegalArgumentException("Repository specification must not be null")
        }

        String name = extractStringField(repoSpec, "name")
        String format = extractStringField(repoSpec, "format")
        String type = extractStringField(repoSpec, "type")

        if (!ValidationUtils.isNonEmpty(name)) {
            throw new IllegalArgumentException("Repository 'name' is required")
        }
        if (!ValidationUtils.isNonEmpty(format)) {
            throw new IllegalArgumentException("Repository 'format' is required for '${name}'")
        }
        if (!ValidationUtils.isNonEmpty(type)) {
            throw new IllegalArgumentException("Repository 'type' is required for '${name}'")
        }

        String normalizedFormat = format.toLowerCase()
        String normalizedType = type.toLowerCase()

        if (!VALID_FORMATS.contains(normalizedFormat)) {
            LoggingUtils.warn("RepositoryLifecycleManager",
                "Repository '${name}' format '${normalizedFormat}' is not in the standard set: ${VALID_FORMATS} [correlationId=${correlationId}]")
        }
        if (!VALID_TYPES.contains(normalizedType)) {
            throw new IllegalArgumentException("Repository '${name}' type '${normalizedType}' is invalid. Must be one of: ${VALID_TYPES}")
        }

        LoggingUtils.info("RepositoryLifecycleManager",
            "Creating ${normalizedType} repository '${name}' (${normalizedFormat}) [correlationId=${correlationId}]")

        try {
            String endpointPath = buildEndpointPath(normalizedFormat, normalizedType)
            String repoUrl = "${this.nexusUrl}${endpointPath}"
            Map requestBody = buildRepositoryRequestBody(name, normalizedFormat, normalizedType, repoSpec)
            String jsonPayload = convertToJson(requestBody)

            Map response = retry.retry("CreateRepository_${name}", {
                def httpResponse = steps.httpRequest(
                    url: repoUrl,
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
                LoggingUtils.info("RepositoryLifecycleManager",
                    "Repository '${name}' created successfully [correlationId=${correlationId}]")
                audit.emitAuditEvent("REPOSITORY_CREATED",
                    "${normalizedType} repository '${name}' (${normalizedFormat}) created", correlationId)
                telemetry.emitEvent("nexus.repository", "created", [
                    correlationId: correlationId,
                    repositoryName: name,
                    format: normalizedFormat,
                    type: normalizedType
                ])
                return [name: name, format: normalizedFormat, type: normalizedType, status: "CREATED"]

            } else if (statusCode == 400) {
                String responseBody = response.content != null ? response.content.toString() : ""
                if (responseBody.contains("already exists")) {
                    LoggingUtils.info("RepositoryLifecycleManager",
                        "Repository '${name}' already exists (idempotent) [correlationId=${correlationId}]")
                    audit.emitAuditEvent("REPOSITORY_ALREADY_EXISTS",
                        "Repository '${name}' already exists, proceeding", correlationId)
                    telemetry.emitEvent("nexus.repository", "already_exists", [
                        correlationId: correlationId,
                        repositoryName: name
                    ])
                    return [name: name, format: normalizedFormat, type: normalizedType, status: "ALREADY_EXISTS"]
                }
                String errMsg = "Failed to create repository '${name}': HTTP ${statusCode} - ${responseBody}"
                LoggingUtils.error("RepositoryLifecycleManager", errMsg, null)
                audit.emitAuditEvent("REPOSITORY_CREATE_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)

            } else if (statusCode == 401 || statusCode == 403) {
                String errMsg = "Authentication failed for Nexus repository API. Verify credentials '${credentialsId}'."
                audit.emitAuditEvent("REPOSITORY_AUTH_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)

            } else {
                String responseBody = response.content != null ? response.content.toString() : ""
                String errMsg = "Unexpected HTTP ${statusCode} creating repository '${name}': ${responseBody}"
                LoggingUtils.error("RepositoryLifecycleManager", errMsg, null)
                audit.emitAuditEvent("REPOSITORY_CREATE_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg)
            }

        } catch (RuntimeException e) {
            if (e.message != null && e.message.contains("already exists")) {
                throw e
            }
            LoggingUtils.error("RepositoryLifecycleManager",
                "Repository creation failed for '${name}': ${e.message}", e)
            audit.emitAuditEvent("REPOSITORY_CREATE_ERROR",
                "Error creating repository '${name}': ${e.message}", correlationId)
            throw e

        } catch (Exception e) {
            String errMsg = "Unexpected error creating repository '${name}': ${e.message}"
            LoggingUtils.error("RepositoryLifecycleManager", errMsg, e)
            audit.emitAuditEvent("REPOSITORY_CREATE_ERROR", errMsg, correlationId)
            throw new RuntimeException(errMsg, e)
        }
    }

    Boolean repositoryExists(String name) {
        if (!ValidationUtils.isNonEmpty(name)) return false

        try {
            List<Map> repos = listRepositories()
            for (Map repo : repos) {
                String repoName = repo.name instanceof String ? repo.name.toString() : ""
                if (repoName.equals(name)) return true
            }
            return false

        } catch (Exception e) {
            LoggingUtils.warn("RepositoryLifecycleManager",
                "Failed to check if repository '${name}' exists: ${e.message}")
            return false
        }
    }

    List<Map> listRepositories() {
        String listUrl = "${this.nexusUrl}${REPOSITORIES_API_PATH}"

        try {
            Map response = retry.retry("ListRepositories", {
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

            LoggingUtils.warn("RepositoryLifecycleManager",
                "Failed to list repositories: HTTP ${statusCode} [correlationId=${correlationId}]")
            return []

        } catch (Exception e) {
            LoggingUtils.warn("RepositoryLifecycleManager",
                "Failed to list repositories: ${e.message} [correlationId=${correlationId}]")
            return []
        }
    }

    Map deleteRepository(String name) {
        LoggingUtils.info("RepositoryLifecycleManager",
            "Deleting repository '${name}' [correlationId=${correlationId}]")
        if (!ValidationUtils.isNonEmpty(name)) {
            return [name: name, status: "SKIPPED"]
        }

        String deleteUrl = "${this.nexusUrl}${REPOSITORIES_API_PATH}/${urlEncode(name)}"

        try {
            Map response = retry.retry("DeleteRepository_${name}", {
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
                audit.emitAuditEvent("REPOSITORY_DELETED",
                    "Repository '${name}' deleted", correlationId)
                return [name: name, status: "DELETED"]
            }
            if (statusCode == 404) {
                return [name: name, status: "NOT_FOUND"]
            }
            return [name: name, status: "DELETE_FAILED"]

        } catch (Exception e) {
            LoggingUtils.warn("RepositoryLifecycleManager",
                "Failed to delete repository '${name}': ${e.message}")
            return [name: name, status: "DELETE_FAILED"]
        }
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String buildEndpointPath(String format, String type) {
        StringBuilder path = new StringBuilder(REPOSITORIES_API_PATH)
        path.append("/").append(format).append("/").append(type)
        return path.toString()
    }

    @NonCPS
    private Map buildRepositoryRequestBody(String name, String format, String type, Map spec) {
        Map body = [:]
        body["name"] = name
        body["format"] = format
        body["type"] = type

        String blobStoreName = extractStringField(spec, "blobStore")
        if (ValidationUtils.isNonEmpty(blobStoreName)) {
            body["blobStoreName"] = blobStoreName
        } else {
            body["blobStoreName"] = "default"
        }

        Boolean strictContentValidation = spec.strictContentValidation instanceof Boolean ?
            spec.strictContentValidation : true
        body["strictContentTypeValidation"] = strictContentValidation

        Boolean online = spec.online instanceof Boolean ? spec.online : true
        body["online"] = online

        if ("hosted".equals(type)) {
            Map hosted = [:]
            String writePolicy = extractStringField(spec, "writePolicy")
            if (ValidationUtils.isNonEmpty(writePolicy)) {
                hosted["writePolicy"] = writePolicy.toUpperCase()
            } else {
                boolean isSnapshot = name.toLowerCase().contains("snapshot")
                hosted["writePolicy"] = isSnapshot ? "ALLOW" : "ALLOW_ONCE"
            }
            if (!hosted.isEmpty()) {
                body["hosted"] = hosted
            }
        }

        if ("proxy".equals(type)) {
            Map proxy = [:]
            String remoteUrl = extractStringField(spec, "remoteUrl")
            if (ValidationUtils.isNonEmpty(remoteUrl)) {
                proxy["remoteUrl"] = remoteUrl
            }
            Object contentMaxAgeRaw = spec.contentMaxAge
            if (contentMaxAgeRaw instanceof Number) {
                proxy["contentMaxAge"] = ((Number) contentMaxAgeRaw).intValue()
            } else {
                proxy["contentMaxAge"] = 1440
            }
            Object metadataMaxAgeRaw = spec.metadataMaxAge
            if (metadataMaxAgeRaw instanceof Number) {
                proxy["metadataMaxAge"] = ((Number) metadataMaxAgeRaw).intValue()
            } else {
                proxy["metadataMaxAge"] = 1440
            }
            if (!proxy.isEmpty()) {
                body["proxy"] = proxy
            }

            Map negativeCache = [:]
            Object ncEnabledRaw = spec.negativeCacheEnabled
            if (ncEnabledRaw instanceof Boolean) {
                negativeCache["enabled"] = ncEnabledRaw
            } else {
                negativeCache["enabled"] = true
            }
            Object ncTtlRaw = spec.negativeCacheTtl
            if (ncTtlRaw instanceof Number) {
                negativeCache["timeToLive"] = ((Number) ncTtlRaw).intValue()
            } else {
                negativeCache["timeToLive"] = 1440
            }
            if (!negativeCache.isEmpty()) {
                body["negativeCache"] = negativeCache
            }

            Map httpClient = [:]
            httpClient["blocked"] = spec.blocked instanceof Boolean ? spec.blocked : false
            httpClient["autoBlock"] = spec.autoBlock instanceof Boolean ? spec.autoBlock : true
            body["httpClient"] = httpClient
        }

        if ("group".equals(type)) {
            Map group = [:]
            Object memberNamesRaw = spec.memberNames
            if (memberNamesRaw instanceof List) {
                List memberNames = (List) memberNamesRaw
                List<String> members = []
                for (Object member : memberNames) {
                    if (member instanceof String && ValidationUtils.isNonEmpty((String) member)) {
                        members.add((String) member)
                    }
                }
                if (!members.isEmpty()) {
                    group["memberNames"] = members
                }
            }
            Object writableMemberRaw = spec.writableMember
            if (writableMemberRaw instanceof String && ValidationUtils.isNonEmpty((String) writableMemberRaw)) {
                group["writableMember"] = (String) writableMemberRaw
            }
            if (!group.isEmpty()) {
                body["group"] = group
            }
        }

        Object cleanupRaw = spec.cleanup
        if (cleanupRaw instanceof Map) {
            Map cleanup = (Map) cleanupRaw
            Object policyNamesRaw = cleanup.get("policyNames")
            if (policyNamesRaw instanceof List) {
                List policyList = (List) policyNamesRaw
                List<String> policies = []
                for (Object p : policyList) {
                    if (p instanceof String) policies.add((String) p)
                }
                if (!policies.isEmpty()) {
                    body["cleanup"] = [policyNames: policies]
                }
            }
        }

        return body
    }

    private List<Map<String, Object>> buildAuthHeaders() {
        return [
            [name: "Authorization", value: "\${NEXUS_AUTH_HEADER}"]
        ]
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
            LoggingUtils.warn("RepositoryLifecycleManager",
                "Failed to parse repository JSON response: ${e.message}")
        }
        return result
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
