package com.enterprise.platform.branching

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class MultibranchOrchestrator implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String DEFAULT_JENKINSFILE_PATH = "Jenkinsfile.platform"
    private static final int DEFAULT_ORPHAN_DAYS_TO_KEEP = 30
    private static final int DEFAULT_ORPHAN_NUM_TO_KEEP = 50

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    MultibranchOrchestrator(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    MultibranchOrchestrator(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map configureMultibranchProject(Map projectConfig) {
        LoggingUtils.info("MultibranchOrchestrator",
            "Configuring multibranch project [correlationId=${correlationId}]")

        if (projectConfig == null) {
            throw new IllegalArgumentException("Project configuration must not be null")
        }

        try {
            Map metadata = extractMapField(projectConfig, "metadata") ?:
                extractMapField(projectConfig, "project.metadata") ?:
                extractMapField(projectConfig, "project")

            String projectName = extractStringField(metadata, "projectName")
            String repoUrl = extractStringField(metadata, "repositoryUrl")
            String scmCredentialsId = extractStringField(metadata, "scmCredentialsId")
            String jenkinsfilePath = DEFAULT_JENKINSFILE_PATH

            if (!ValidationUtils.isNonEmpty(projectName)) {
                projectName = extractStringField(projectConfig, "name") ?: "unknown-project"
            }
            if (!ValidationUtils.isNonEmpty(repoUrl)) {
                throw new IllegalArgumentException("repositoryUrl is required for multibranch configuration")
            }
            if (ValidationUtils.isNonEmpty(extractStringField(metadata, "jenkinsfilePath"))) {
                jenkinsfilePath = extractStringField(metadata, "jenkinsfilePath")
            }

            Map branchGovernance = extractMapField(projectConfig, "branchGovernance") ?: [:]
            Map scmConfig = buildScmConfiguration(repoUrl, scmCredentialsId, branchGovernance)

            Map orphanConfig = buildOrphanedItemStrategy(projectConfig)

            LoggingUtils.info("MultibranchOrchestrator",
                "Multibranch project configured: '${projectName}' with ${scmConfig.branchDiscovery ?: 'default'} discovery [correlationId=${correlationId}]")

            audit.emitAuditEvent("MULTIBRANCH_CONFIGURED",
                "Multibranch project '${projectName}' configured with Jenkinsfile '${jenkinsfilePath}'", correlationId)
            telemetry.emitEvent("branching.multibranch", "configured", [
                correlationId: correlationId,
                projectName: projectName,
                repositoryUrl: repoUrl,
                jenkinsfilePath: jenkinsfilePath
            ])

            return [
                status: "CONFIGURED",
                projectName: projectName,
                repositoryUrl: repoUrl,
                jenkinsfilePath: jenkinsfilePath,
                scmConfig: scmConfig,
                orphanConfig: orphanConfig,
                branchGovernance: branchGovernance
            ]

        } catch (Exception e) {
            String errMsg = "Multibranch project configuration failed: ${e.message}"
            LoggingUtils.error("MultibranchOrchestrator", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map indexBranches(String jobPath) {
        LoggingUtils.info("MultibranchOrchestrator",
            "Triggering branch indexing for '${jobPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(jobPath)) {
            throw new IllegalArgumentException("Job path must not be null or empty")
        }

        try {
            steps.stage("Branch Indexing") {
                steps.build(
                    job: jobPath,
                    wait: false,
                    propagate: false
                )
            }

            audit.emitAuditEvent("BRANCH_INDEXING_TRIGGERED",
                "Branch indexing triggered for '${jobPath}'", correlationId)
            telemetry.emitEvent("branching.indexing", "triggered", [
                correlationId: correlationId,
                jobPath: jobPath
            ])

            return [status: "INDEXING_TRIGGERED", jobPath: jobPath]

        } catch (Exception e) {
            LoggingUtils.warn("MultibranchOrchestrator",
                "Branch indexing trigger failed for '${jobPath}': ${e.message}")
            return [status: "INDEXING_FAILED", jobPath: jobPath, error: e.message]
        }
    }

    Map getBranchBuildStatus(String jobPath, String branchName) {
        if (!ValidationUtils.isNonEmpty(jobPath) || !ValidationUtils.isNonEmpty(branchName)) {
            return [status: "UNKNOWN"]
        }

        try {
            def job = steps.Jenkins.instance.getItemByFullName("${jobPath}/${branchName}")
            if (job == null) {
                return [status: "NO_JOB_FOUND", branch: branchName]
            }

            def lastBuild = job.getLastBuild()
            if (lastBuild == null) {
                return [status: "NO_BUILDS", branch: branchName]
            }

            return [
                status: "FOUND",
                branch: branchName,
                lastBuildNumber: lastBuild.getNumber(),
                lastBuildResult: lastBuild.getResult()?.toString() ?: "UNKNOWN",
                lastBuildTimestamp: lastBuild.getTimeInMillis(),
                lastBuildDuration: lastBuild.getDuration()
            ]

        } catch (Exception e) {
            LoggingUtils.warn("MultibranchOrchestrator",
                "Failed to get branch build status for '${branchName}': ${e.message}")
            return [status: "ERROR", branch: branchName, error: e.message]
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map buildScmConfiguration(String repoUrl, String credentialsId, Map branchGovernance) {
        Map scm = [:]
        scm["url"] = repoUrl
        scm["credentialsId"] = ValidationUtils.isNonEmpty(credentialsId) ?
            credentialsId : "git"
        scm["branchDiscovery"] = "ALL_BRANCHES"

        List<String> include = []
        List<String> exclude = []

        Object includeRaw = branchGovernance.get("include")
        if (includeRaw instanceof List) {
            for (Object item : (List) includeRaw) {
                if (item instanceof String) include.add((String) item)
            }
        }
        Object excludeRaw = branchGovernance.get("exclude")
        if (excludeRaw instanceof List) {
            for (Object item : (List) excludeRaw) {
                if (item instanceof String) exclude.add((String) item)
            }
        }

        if (!include.isEmpty()) {
            scm["includePatterns"] = include
        }
        if (!exclude.isEmpty()) {
            scm["excludePatterns"] = exclude
        }

        return scm
    }

    @NonCPS
    private Map buildOrphanedItemStrategy(Map config) {
        int daysToKeep = DEFAULT_ORPHAN_DAYS_TO_KEEP
        int numToKeep = DEFAULT_ORPHAN_NUM_TO_KEEP

        Map cleanup = extractMapField(config, "orphanedItemCleanup") ?:
            extractMapField(config, "cleanup")

        if (cleanup != null) {
            if (cleanup.daysToKeep instanceof Number) {
                daysToKeep = ((Number) cleanup.daysToKeep).intValue()
            }
            if (cleanup.numToKeep instanceof Number) {
                numToKeep = ((Number) cleanup.numToKeep).intValue()
            }
        }

        return [daysToKeep: daysToKeep, numToKeep: numToKeep]
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
