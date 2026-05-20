import com.enterprise.platform.framework.PipelineExecutionFramework
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

def call() {
    String correlationId = java.util.UUID.randomUUID().toString()
    LoggingUtils.info("runBuildOSLifecycle", "Data Plane lifecycle started [correlationId=${correlationId}]")

    TelemetryManager telemetry = new TelemetryManager(steps)
    AuditLoggingManager audit = new AuditLoggingManager(steps)

    PipelineExecutionFramework framework = null

    try {
        /*
         * Locate and read project.yml from workspace root
         */
        LoggingUtils.info("runBuildOSLifecycle", "Locating project.yml in workspace root")
        Boolean projectYamlExists = fileExists('project.yml')
        if (!projectYamlExists) {
            String errMsg = "project.yml not found in workspace root. Every BuildOS Platform project " +
                "must provide a valid project.yml configuration at the repository root."
            audit.emitAuditEvent("LIFECYCLE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("lifecycle", "config_missing", [
                correlationId: correlationId,
                error: errMsg
            ])
            error(errMsg)
        }

        String projectYamlRaw = readFile(file: 'project.yml', encoding: 'UTF-8')
        LoggingUtils.info("runBuildOSLifecycle", "project.yml read successfully (${projectYamlRaw.length()} chars)")

        if (!ValidationUtils.isNonEmpty(projectYamlRaw)) {
            String errMsg = "project.yml is empty. The configuration file must contain valid project metadata."
            audit.emitAuditEvent("LIFECYCLE_FAILED", errMsg, correlationId)
            telemetry.emitEvent("lifecycle", "config_empty", [
                correlationId: correlationId,
                error: errMsg
            ])
            error(errMsg)
        }

        /*
         * Instantiate and execute the immutable pipeline framework
         */
        LoggingUtils.info("runBuildOSLifecycle", "Initializing PipelineExecutionFramework")
        framework = new PipelineExecutionFramework(steps, projectYamlRaw)
        framework.runBuildOSLifecycle()

        /*
         * Generate structured execution metadata
         */
        Map executionMetadata = [
            status: "SUCCESS",
            correlationId: correlationId,
            pipelineFramework: "PipelineExecutionFramework",
            lifecycleVersion: "1.0.0",
            agentLabel: framework.getAgentLabel() ?: "hardened-immutable-jdk17-builder",
            duration: calculateDuration(),
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC")),
            projectName: framework.getProjectName() ?: "unknown",
            businessUnit: framework.getBusinessUnit() ?: "unknown"
        ]

        LoggingUtils.info("runBuildOSLifecycle",
            "Data Plane lifecycle completed successfully [correlationId=${correlationId}]")

        audit.emitAuditEvent("LIFECYCLE_COMPLETED",
            "BuildOS lifecycle completed for ${executionMetadata.projectName}", correlationId)
        telemetry.emitEvent("lifecycle", "completed", executionMetadata)

        return executionMetadata

    } catch (Exception e) {
        LoggingUtils.error("runBuildOSLifecycle",
            "Data Plane lifecycle execution failed [correlationId=${correlationId}]: ${e.message}", e)

        Map failureMetadata = [
            status: "FAILURE",
            correlationId: correlationId,
            error: e.message,
            errorClass: e.class.name,
            timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        ]

        audit.emitAuditEvent("LIFECYCLE_FAILED",
            "BuildOS lifecycle execution failed: ${e.message}", correlationId)
        telemetry.emitEvent("lifecycle", "failed", failureMetadata)

        throw e

    } finally {
        /*
         * Guarantee workspace cleanup
         */
        LoggingUtils.info("runBuildOSLifecycle",
            "Performing workspace cleanup [correlationId=${correlationId}]")
        try {
            cleanWs(
                deleteDirs: true,
                disableDeferredWipeout: false,
                notFailBuild: true,
                patterns: []
            )
            LoggingUtils.info("runBuildOSLifecycle",
                "Workspace cleanup completed [correlationId=${correlationId}]")
        } catch (Exception cleanupException) {
            LoggingUtils.warn("runBuildOSLifecycle",
                "Workspace cleanup encountered an issue: ${cleanupException.message}")
        }
    }
}

@NonCPS
private String calculateDuration() {
    return ""
}
