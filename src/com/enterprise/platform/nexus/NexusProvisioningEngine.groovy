package com.enterprise.platform.nexus

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class NexusProvisioningEngine implements Serializable {
    private static final long serialVersionUID = 1L

    private final Object steps
    private final String nexusUrl
    private final String credentialsId
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final BlobStoreManager blobStoreManager
    private final RepositoryLifecycleManager repositoryLifecycleManager
    private final CleanupPolicyManager cleanupPolicyManager
    private final RepositoryGovernanceManager repositoryGovernanceManager
    private final String correlationId

    NexusProvisioningEngine(Object steps, String nexusUrl, String credentialsId) {
        this.steps = steps
        this.nexusUrl = nexusUrl
        this.credentialsId = credentialsId
        this.correlationId = java.util.UUID.randomUUID().toString()
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.blobStoreManager = new BlobStoreManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.repositoryLifecycleManager = new RepositoryLifecycleManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.cleanupPolicyManager = new CleanupPolicyManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.repositoryGovernanceManager = new RepositoryGovernanceManager(steps, this.correlationId)
    }

    NexusProvisioningEngine(Object steps, String nexusUrl, String credentialsId, String correlationId) {
        this.steps = steps
        this.nexusUrl = nexusUrl
        this.credentialsId = credentialsId
        this.correlationId = correlationId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.blobStoreManager = new BlobStoreManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.repositoryLifecycleManager = new RepositoryLifecycleManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.cleanupPolicyManager = new CleanupPolicyManager(steps, nexusUrl, credentialsId, this.correlationId)
        this.repositoryGovernanceManager = new RepositoryGovernanceManager(steps, this.correlationId)
    }

    Map enforceRepositoryFootprint(Map configurationMap) {
        LoggingUtils.info("NexusProvisioningEngine",
            "Enforcing Nexus repository footprint [correlationId=${correlationId}]")

        if (configurationMap == null || configurationMap.isEmpty()) {
            String errMsg = "Configuration map is null or empty. Cannot enforce repository footprint."
            audit.emitAuditEvent("NEXUS_FOOTPRINT_FAILED", errMsg, correlationId)
            telemetry.emitEvent("nexus", "footprint_failed", [
                correlationId: correlationId,
                error: errMsg
            ])
            throw new IllegalArgumentException(errMsg)
        }

        long startTime = System.currentTimeMillis()
        List<Map> provisioningResults = []
        List<Map> governanceResults = []

        try {
            Map nexusConfig = extractNexusConfig(configurationMap)
            if (nexusConfig == null || nexusConfig.isEmpty()) {
                LoggingUtils.warn("NexusProvisioningEngine",
                    "No Nexus configuration found in project config. Skipping Nexus provisioning. [correlationId=${correlationId}]")
                audit.emitAuditEvent("NEXUS_FOOTPRINT_SKIPPED",
                    "Nexus provisioning skipped: no nexus configuration block", correlationId)
                return [
                    status: "SKIPPED",
                    correlationId: correlationId,
                    reason: "No nexus configuration found",
                    durationMs: System.currentTimeMillis() - startTime,
                    provisioningResults: [],
                    governanceResults: []
                ]
            }

            /*
             * Phase 1: Governance validation
             */
            LoggingUtils.info("NexusProvisioningEngine",
                "Phase 1: Running governance validation [correlationId=${correlationId}]")
            try {
                governanceResults = repositoryGovernanceManager.validateRepositoryGovernance(nexusConfig)
                if (repositoryGovernanceManager.hasViolations()) {
                    List<String> violations = repositoryGovernanceManager.getViolations()
                    String violationsSummary = violations.join("; ")
                    LoggingUtils.error("NexusProvisioningEngine",
                        "Governance validation found ${violations.size()} violation(s): ${violationsSummary}", null)
                    audit.emitAuditEvent("NEXUS_GOVERNANCE_FAILED",
                        "Governance violations found: ${violationsSummary}", correlationId)
                    telemetry.emitEvent("nexus", "governance_failed", [
                        correlationId: correlationId,
                        violationCount: violations.size(),
                        violations: violations
                    ])
                    throw new RuntimeException(
                        "Nexus governance validation failed with ${violations.size()} violation(s): ${violationsSummary}")
                }
                if (repositoryGovernanceManager.hasWarnings()) {
                    LoggingUtils.warn("NexusProvisioningEngine",
                        "Governance validation produced ${repositoryGovernanceManager.getWarnings().size()} warning(s) [correlationId=${correlationId}]")
                }
                LoggingUtils.info("NexusProvisioningEngine",
                    "Governance validation passed [correlationId=${correlationId}]")
            } catch (RuntimeException e) {
                if (e.message.contains("Governance violations")) {
                    throw e
                }
                LoggingUtils.error("NexusProvisioningEngine",
                    "Governance validation error: ${e.message}", e)
                throw new RuntimeException("Governance validation failed: ${e.message}", e)
            }

            /*
             * Phase 2: Blob store provisioning
             */
            LoggingUtils.info("NexusProvisioningEngine",
                "Phase 2: Provisioning blob stores [correlationId=${correlationId}]")
            try {
                String blobStoreName = extractStringField(nexusConfig, "blobStore")
                if (ValidationUtils.isNonEmpty(blobStoreName)) {
                    Map blobStoreConfig = extractMapField(nexusConfig, "blobStoreConfig")
                    if (blobStoreConfig == null) blobStoreConfig = [:]
                    Map blobResult = blobStoreManager.createBlobStore(blobStoreName, blobStoreConfig)
                    provisioningResults.add([
                        phase: "BLOB_STORE",
                        resourceName: blobStoreName,
                        status: blobResult.status
                    ])
                    LoggingUtils.info("NexusProvisioningEngine",
                        "Blob store '${blobStoreName}' provisioned: ${blobResult.status} [correlationId=${correlationId}]")
                } else {
                    LoggingUtils.info("NexusProvisioningEngine",
                        "No blob store specified, using default [correlationId=${correlationId}]")
                }
            } catch (Exception e) {
                String errMsg = "Blob store provisioning failed: ${e.message}"
                LoggingUtils.error("NexusProvisioningEngine", errMsg, e)
                audit.emitAuditEvent("NEXUS_BLOB_STORE_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg, e)
            }

            /*
             * Phase 3: Cleanup policy provisioning
             */
            LoggingUtils.info("NexusProvisioningEngine",
                "Phase 3: Provisioning cleanup policies [correlationId=${correlationId}]")
            try {
                Map cleanupConfig = extractMapField(nexusConfig, "cleanupPolicies")
                if (cleanupConfig != null && !cleanupConfig.isEmpty()) {
                    for (Map.Entry entry : cleanupConfig.entrySet()) {
                        String policyName = entry.key.toString()
                        Object policyValue = entry.value
                        Map policyConfig = policyValue instanceof Map ? (Map) policyValue : [:]
                        Map policyResult = cleanupPolicyManager.createCleanupPolicy(policyName, policyConfig)
                        provisioningResults.add([
                            phase: "CLEANUP_POLICY",
                            resourceName: policyName,
                            status: policyResult.status
                        ])
                        LoggingUtils.info("NexusProvisioningEngine",
                            "Cleanup policy '${policyName}' provisioned: ${policyResult.status} [correlationId=${correlationId}]")
                    }
                } else {
                    LoggingUtils.info("NexusProvisioningEngine",
                        "No cleanup policies to provision [correlationId=${correlationId}]")
                }
            } catch (Exception e) {
                String errMsg = "Cleanup policy provisioning failed: ${e.message}"
                LoggingUtils.error("NexusProvisioningEngine", errMsg, e)
                audit.emitAuditEvent("NEXUS_CLEANUP_POLICY_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg, e)
            }

            /*
             * Phase 4: Repository provisioning
             */
            LoggingUtils.info("NexusProvisioningEngine",
                "Phase 4: Provisioning repositories [correlationId=${correlationId}]")
            try {
                Object reposRaw = nexusConfig.get("repositories")
                if (reposRaw instanceof List) {
                    List repos = (List) reposRaw
                    if (repos.isEmpty()) {
                        LoggingUtils.info("NexusProvisioningEngine",
                            "No repositories to provision [correlationId=${correlationId}]")
                    } else {
                        for (int i = 0; i < repos.size(); i++) {
                            Object repoObj = repos.get(i)
                            if (repoObj instanceof Map) {
                                Map repoSpec = (Map) repoObj
                                String blobStoreName = extractStringField(nexusConfig, "blobStore")
                                if (ValidationUtils.isNonEmpty(blobStoreName) && !repoSpec.containsKey("blobStore")) {
                                    repoSpec["blobStore"] = blobStoreName
                                }
                                String repoName = extractStringField(repoSpec, "name")
                                Map repoResult = repositoryLifecycleManager.createRepository(repoSpec)
                                provisioningResults.add([
                                    phase: "REPOSITORY",
                                    resourceName: repoName,
                                    format: repoResult.format,
                                    type: repoResult.type,
                                    status: repoResult.status
                                ])
                                LoggingUtils.info("NexusProvisioningEngine",
                                    "Repository '${repoName}' provisioned: ${repoResult.status} [correlationId=${correlationId}]")
                            }
                        }
                    }
                } else {
                    LoggingUtils.info("NexusProvisioningEngine",
                        "No repositories defined in nexus configuration [correlationId=${correlationId}]")
                }
            } catch (Exception e) {
                String errMsg = "Repository provisioning failed: ${e.message}"
                LoggingUtils.error("NexusProvisioningEngine", errMsg, e)
                audit.emitAuditEvent("NEXUS_REPOSITORY_FAILED", errMsg, correlationId)
                throw new RuntimeException(errMsg, e)
            }

            /*
             * Complete
             */
            long endTime = System.currentTimeMillis()
            long totalDuration = endTime - startTime
            int successCount = countStatus(provisioningResults, "CREATED", "UPDATED")
            int alreadyExistsCount = countStatus(provisioningResults, "ALREADY_EXISTS")

            LoggingUtils.info("NexusProvisioningEngine",
                "Nexus repository footprint enforced in ${totalDuration}ms: " +
                "${successCount} created/updated, ${alreadyExistsCount} already existed, " +
                "${provisioningResults.size()} total operations [correlationId=${correlationId}]")

            audit.emitAuditEvent("NEXUS_FOOTPRINT_COMPLETED",
                "Nexus provisioning completed: ${successCount} resources provisioned, ${alreadyExistsCount} already existed", correlationId)
            telemetry.emitEvent("nexus", "footprint_completed", [
                correlationId: correlationId,
                durationMs: totalDuration,
                totalOperations: provisioningResults.size(),
                successCount: successCount,
                alreadyExistsCount: alreadyExistsCount,
                governanceViolations: governanceResults.size()
            ])

            return [
                status: "COMPLETED",
                correlationId: correlationId,
                durationMs: totalDuration,
                nexusUrl: this.nexusUrl,
                provisioningResults: provisioningResults,
                governanceResults: governanceResults
            ]

        } catch (RuntimeException e) {
            long endTime = System.currentTimeMillis()
            LoggingUtils.error("NexusProvisioningEngine",
                "Nexus provisioning failed: ${e.message} [correlationId=${correlationId}]", e)
            audit.emitAuditEvent("NEXUS_FOOTPRINT_ERROR",
                "Nexus provisioning error: ${e.message}", correlationId)
            telemetry.emitEvent("nexus", "footprint_failed", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name,
                durationMs: endTime - startTime,
                provisioningResults: provisioningResults.size()
            ])
            throw new RuntimeException("Nexus provisioning failed: ${e.message}", e)

        } catch (Exception e) {
            long endTime = System.currentTimeMillis()
            String errMsg = "Unexpected Nexus provisioning error: ${e.message}"
            LoggingUtils.error("NexusProvisioningEngine", errMsg, e)
            audit.emitAuditEvent("NEXUS_FOOTPRINT_UNEXPECTED_ERROR", errMsg, correlationId)
            telemetry.emitEvent("nexus", "footprint_unexpected_error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name,
                durationMs: endTime - startTime
            ])
            throw new RuntimeException(errMsg, e)
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    String getNexusUrl() {
        return this.nexusUrl
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map extractNexusConfig(Map configurationMap) {
        if (configurationMap == null) return null
        Object nexusRaw = configurationMap.get("nexus")
        if (nexusRaw instanceof Map) return (Map) nexusRaw
        Object repositoriesRaw = configurationMap.get("repositories")
        if (repositoriesRaw instanceof List && nexusRaw == null) {
            Map inferred = [:]
            inferred["repositories"] = repositoriesRaw
            return inferred
        }
        return nexusRaw instanceof Map ? (Map) nexusRaw : null
    }

    @NonCPS
    private int countStatus(List<Map> results, String... statuses) {
        Set<String> statusSet = new HashSet<>(Arrays.asList(statuses))
        int count = 0
        for (Map result : results) {
            Object s = result.get("status")
            if (s instanceof String && statusSet.contains((String) s)) {
                count++
            }
        }
        return count
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
