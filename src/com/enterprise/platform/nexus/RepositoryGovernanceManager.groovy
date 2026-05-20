package com.enterprise.platform.nexus

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class RepositoryGovernanceManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int MAX_REPOSITORY_NAME_LENGTH = 80
    private static final List<String> ALLOWED_FORMATS = ["maven2", "npm", "pypi", "docker", "raw"]
    private static final List<String> ALLOWED_TYPES = ["hosted", "proxy", "group"]
    private static final List<String> PROHIBITED_NAME_PREFIXES = ["test-", "tmp-", "temp-", "delete-", "trash-"]
    private static final String DOMAIN_PATTERN = "^[a-z0-9]+(-[a-z0-9]+)*(\\.[a-z0-9]+(-[a-z0-9]+)*)*$"

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<String> governanceViolations = []
    private final List<String> governanceWarnings = []

    RepositoryGovernanceManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    RepositoryGovernanceManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    List<Map> validateRepositoryGovernance(Map nexusConfig) {
        LoggingUtils.info("RepositoryGovernanceManager",
            "Validating repository governance [correlationId=${correlationId}]")
        governanceViolations.clear()
        governanceWarnings.clear()

        List<Map> governanceResults = []

        if (nexusConfig == null || nexusConfig.isEmpty()) {
            addViolation("Nexus configuration is null or empty. Governance validation cannot proceed.")
            governanceResults.add([
                type: "GOVERNANCE_ERROR",
                severity: "CRITICAL",
                message: "Nexus configuration is null or empty"
            ])
            return governanceResults
        }

        try {
            String blobStore = extractStringField(nexusConfig, "blobStore")
            if (ValidationUtils.isNonEmpty(blobStore)) {
                Map blobResult = validateBlobStoreName(blobStore)
                if (blobResult != null) {
                    governanceResults.add(blobResult)
                }
            } else {
                addWarning("No blob store name specified. Default blob store will be used.")
                governanceResults.add([
                    type: "GOVERNANCE_WARNING",
                    severity: "WARNING",
                    message: "No blob store name specified, default will be used",
                    field: "nexus.blobStore"
                ])
            }

            Object reposRaw = nexusConfig.get("repositories")
            if (reposRaw instanceof List) {
                List repos = (List) reposRaw
                if (repos.isEmpty()) {
                    addWarning("Nexus repositories list is empty. No repositories will be provisioned.")
                    governanceResults.add([
                        type: "GOVERNANCE_WARNING",
                        severity: "WARNING",
                        message: "Repository list is empty"
                    ])
                } else {
                    for (int i = 0; i < repos.size(); i++) {
                        Object repoObj = repos.get(i)
                        if (repoObj instanceof Map) {
                            List<Map> repoResults = validateSingleRepository((Map) repoObj, i)
                            governanceResults.addAll(repoResults)
                        } else {
                            addViolation("Entry ${i} in nexus.repositories must be a mapping structure, got ${repoObj?.getClass()?.simpleName ?: 'null'}")
                            governanceResults.add([
                                type: "GOVERNANCE_VIOLATION",
                                severity: "ERROR",
                                repositoryIndex: i,
                                message: "Repository entry ${i} is not a valid mapping structure"
                            ])
                        }
                    }
                }
            }

            Map proxyConfig = extractMapField(nexusConfig, "proxy")
            if (proxyConfig != null && !proxyConfig.isEmpty()) {
                List<Map> proxyResults = validateProxyConfiguration(proxyConfig)
                governanceResults.addAll(proxyResults)
            }

            int violationCount = governanceViolations.size()
            int warningCount = governanceWarnings.size()

            LoggingUtils.info("RepositoryGovernanceManager",
                "Governance validation completed: ${violationCount} violations, ${warningCount} warnings [correlationId=${correlationId}]")

            audit.emitAuditEvent("GOVERNANCE_VALIDATION_COMPLETED",
                "Repository governance validation: ${violationCount} violations, ${warningCount} warnings", correlationId)
            telemetry.emitEvent("nexus.governance", "validation_completed", [
                correlationId: correlationId,
                violationCount: violationCount,
                warningCount: warningCount,
                totalResults: governanceResults.size()
            ])

            return governanceResults

        } catch (Exception e) {
            addViolation("Governance validation failed with unexpected error: ${e.message}")
            LoggingUtils.error("RepositoryGovernanceManager",
                "Governance validation error: ${e.message}", e)
            telemetry.emitEvent("nexus.governance", "validation_error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            governanceResults.add([
                type: "GOVERNANCE_ERROR",
                severity: "CRITICAL",
                message: "Unexpected governance validation error: ${e.message}"
            ])
            return governanceResults
        }
    }

    Boolean hasViolations() {
        return !governanceViolations.isEmpty()
    }

    Boolean hasWarnings() {
        return !governanceWarnings.isEmpty()
    }

    List<String> getViolations() {
        return new ArrayList<>(governanceViolations)
    }

    List<String> getWarnings() {
        return new ArrayList<>(governanceWarnings)
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Individual validators
     */

    @NonCPS
    private Map validateBlobStoreName(String blobStore) {
        if (!ValidationUtils.isNonEmpty(blobStore)) return null

        if (!blobStore.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
            addViolation("Blob store name '${blobStore}' must start with a letter and contain only alphanumeric, underscore, or hyphen characters.")
            return [
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                field: "nexus.blobStore",
                value: blobStore,
                message: "Blob store name '${blobStore}' has invalid format"
            ]
        }

        if (blobStore.length() > MAX_REPOSITORY_NAME_LENGTH) {
            addViolation("Blob store name '${blobStore}' exceeds maximum length of ${MAX_REPOSITORY_NAME_LENGTH}.")
            return [
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                field: "nexus.blobStore",
                value: blobStore,
                message: "Blob store name exceeds ${MAX_REPOSITORY_NAME_LENGTH} characters"
            ]
        }

        return null
    }

    @NonCPS
    private List<Map> validateSingleRepository(Map repo, int index) {
        List<Map> results = []

        String name = extractStringField(repo, "name")
        String format = extractStringField(repo, "format")
        String type = extractStringField(repo, "type")

        if (!ValidationUtils.isNonEmpty(name)) {
            addViolation("Repository at index ${index} is missing required 'name' field.")
            results.add([
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                repositoryIndex: index,
                message: "Repository name is required"
            ])
            return results
        }

        if (!ValidationUtils.isNonEmpty(format)) {
            addViolation("Repository '${name}' is missing required 'format' field.")
            results.add([
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                repository: name,
                field: "format",
                message: "Repository format is required"
            ])
        } else {
            String normalizedFormat = format.toLowerCase()
            if (!ALLOWED_FORMATS.contains(normalizedFormat)) {
                addWarning("Repository '${name}' format '${normalizedFormat}' is not in the standard allowed set: ${ALLOWED_FORMATS}")
                results.add([
                    type: "GOVERNANCE_WARNING",
                    severity: "WARNING",
                    repository: name,
                    field: "format",
                    value: normalizedFormat,
                    message: "Format '${normalizedFormat}' may require special configuration"
                ])
            }
        }

        if (!ValidationUtils.isNonEmpty(type)) {
            addViolation("Repository '${name}' is missing required 'type' field.")
            results.add([
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                repository: name,
                field: "type",
                message: "Repository type is required"
            ])
        } else {
            String normalizedType = type.toLowerCase()
            if (!ALLOWED_TYPES.contains(normalizedType)) {
                addViolation("Repository '${name}' type '${normalizedType}' is invalid. Must be one of: ${ALLOWED_TYPES}")
                results.add([
                    type: "GOVERNANCE_VIOLATION",
                    severity: "ERROR",
                    repository: name,
                    field: "type",
                    value: normalizedType,
                    message: "Invalid repository type '${normalizedType}'"
                ])
            }
        }

        if (name.length() > MAX_REPOSITORY_NAME_LENGTH) {
            addViolation("Repository name '${name}' exceeds maximum length of ${MAX_REPOSITORY_NAME_LENGTH}.")
            results.add([
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                repository: name,
                field: "name",
                message: "Repository name exceeds ${MAX_REPOSITORY_NAME_LENGTH} characters"
            ])
        }

        if (!name.matches("^[a-zA-Z][a-zA-Z0-9._-]*$")) {
            addViolation("Repository name '${name}' must start with a letter and contain only alphanumeric, dot, underscore, or hyphen characters.")
            results.add([
                type: "GOVERNANCE_VIOLATION",
                severity: "ERROR",
                repository: name,
                field: "name",
                message: "Repository name has invalid format"
            ])
        }

        for (String prohibitedPrefix : PROHIBITED_NAME_PREFIXES) {
            if (name.toLowerCase().startsWith(prohibitedPrefix)) {
                addViolation("Repository name '${name}' uses prohibited prefix '${prohibitedPrefix}'. Production repositories must not use temporary naming conventions.")
                results.add([
                    type: "GOVERNANCE_VIOLATION",
                    severity: "ERROR",
                    repository: name,
                    field: "name",
                    message: "Repository name uses prohibited prefix '${prohibitedPrefix}'"
                ])
            }
        }

        if ("proxy".equalsIgnoreCase(type)) {
            String remoteUrl = extractStringField(repo, "remoteUrl")
            if (!ValidationUtils.isNonEmpty(remoteUrl)) {
                addViolation("Proxy repository '${name}' requires 'remoteUrl' to specify the remote source.")
                results.add([
                    type: "GOVERNANCE_VIOLATION",
                    severity: "ERROR",
                    repository: name,
                    field: "remoteUrl",
                    message: "Proxy repository requires remoteUrl"
                ])
            } else {
                if (!remoteUrl.startsWith("https://")) {
                    addWarning("Proxy repository '${name}' remote URL '${remoteUrl}' should use HTTPS for secure transport.")
                    results.add([
                        type: "GOVERNANCE_WARNING",
                        severity: "WARNING",
                        repository: name,
                        field: "remoteUrl",
                        value: remoteUrl,
                        message: "Proxy remote URL should use HTTPS"
                    ])
                }
            }
        }

        if ("group".equalsIgnoreCase(type)) {
            Object memberNamesRaw = repo.get("memberNames")
            if (!(memberNamesRaw instanceof List) || ((List) memberNamesRaw).isEmpty()) {
                addWarning("Group repository '${name}' has no member repositories defined. Group will be empty until members are added.")
                results.add([
                    type: "GOVERNANCE_WARNING",
                    severity: "WARNING",
                    repository: name,
                    field: "memberNames",
                    message: "Group repository has no member repositories"
                ])
            }
        }

        if ("hosted".equalsIgnoreCase(type)) {
            String writePolicy = extractStringField(repo, "writePolicy")
            if ("ALLOW_ONCE".equalsIgnoreCase(writePolicy)) {
                boolean isSnapshot = name.toLowerCase().contains("snapshot")
                if (isSnapshot) {
                    addWarning("Repository '${name}' is a snapshot repository with ALLOW_ONCE write policy. Snapshots typically use ALLOW for continuous updates.")
                    results.add([
                        type: "GOVERNANCE_WARNING",
                        severity: "WARNING",
                        repository: name,
                        field: "writePolicy",
                        value: writePolicy,
                        message: "Snapshot repository with ALLOW_ONCE write policy"
                    ])
                }
            }
        }

        return results
    }

    @NonCPS
    private List<Map> validateProxyConfiguration(Map proxyConfig) {
        List<Map> results = []

        Object httpClientRaw = proxyConfig.get("httpClient")
        if (httpClientRaw instanceof Map) {
            Map httpClient = (Map) httpClientRaw
            Object blockedRaw = httpClient.get("blocked")
            if (blockedRaw instanceof Boolean && (Boolean) blockedRaw) {
                addWarning("Nexus HTTP client is configured as blocked. This will prevent proxy repositories from reaching remote sources.")
                results.add([
                    type: "GOVERNANCE_WARNING",
                    severity: "WARNING",
                    field: "nexus.proxy.httpClient.blocked",
                    value: true,
                    message: "HTTP client is blocked, proxy repositories will be unable to fetch remote content"
                ])
            }
        }

        Object authTypeRaw = proxyConfig.get("authenticationType")
        if (authTypeRaw instanceof String && ValidationUtils.isNonEmpty((String) authTypeRaw)) {
            String authType = (String) authTypeRaw
            if (!["username", "ntlm", "bearer"].contains(authType.toLowerCase())) {
                addWarning("Unrecognized proxy authentication type '${authType}'. Expected: username, ntlm, or bearer.")
                results.add([
                    type: "GOVERNANCE_WARNING",
                    severity: "WARNING",
                    field: "nexus.proxy.authenticationType",
                    value: authType,
                    message: "Unrecognized authentication type"
                ])
            }
        }

        return results
    }

    /*
     * Private helpers
     */

    @NonCPS
    private void addViolation(String message) {
        governanceViolations.add(message)
    }

    @NonCPS
    private void addWarning(String message) {
        governanceWarnings.add(message)
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
