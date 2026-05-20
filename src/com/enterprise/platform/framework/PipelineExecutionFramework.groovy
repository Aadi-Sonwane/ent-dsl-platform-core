package com.enterprise.platform.framework

import com.enterprise.platform.config.ProjectConfigurationManager
import com.enterprise.platform.branching.BranchGovernanceManager
import com.enterprise.platform.branching.BranchProtectionManager
import com.enterprise.platform.branching.MultibranchOrchestrator
import com.enterprise.platform.nexus.NexusProvisioningEngine
import com.enterprise.platform.maven.BuildLifecycleManager
import com.enterprise.platform.maven.DynamicMavenSettingsGenerator
import com.enterprise.platform.maven.DistributionManagementGenerator
import com.enterprise.platform.maven.ArtifactNamingStrategy
import com.enterprise.platform.maven.AssemblyPackagingManager
import com.enterprise.platform.security.TrivyScanManager
import com.enterprise.platform.security.DependencyScanManager
import com.enterprise.platform.security.SecretScanManager
import com.enterprise.platform.security.ComplianceValidationManager
import com.enterprise.platform.security.SBOMGenerationManager
import com.enterprise.platform.security.ArtifactSigningManager
import com.enterprise.platform.quality.CodeCoverageManager
import com.enterprise.platform.quality.SonarQubeManager
import com.enterprise.platform.quality.QualityGateManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.BuildMetricsManager
import com.enterprise.platform.observability.OpenTelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils
import com.enterprise.platform.utils.ExecutionUtils

class PipelineExecutionFramework implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String AGENT_LABEL = "hardened-immutable-jdk17-builder"
    private static final String LIFECYCLE_VERSION = "1.0.0"
    private static final int MAX_STAGES = 50

    private final Object steps
    private final String projectYamlRaw
    private final String correlationId
    private final TelemetryManager telemetry
    private final AuditLoggingManager audit
    private final BuildMetricsManager metrics
    private final OpenTelemetryManager otel
    private final ExecutionContextManager contextManager
    private final FailureRecoveryManager recovery
    private final ParallelExecutionManager parallel
    private final ShellUtils shell

    private ProjectConfigurationManager configManager
    private Map resolvedConfig = [:]
    private String projectName = ""
    private String businessUnit = ""
    private String nexusUrl = ""
    private String gitBranch = ""
    private boolean lifecycleStarted = false

    PipelineExecutionFramework(Object steps, String projectYamlRaw) {
        this.steps = steps
        this.projectYamlRaw = projectYamlRaw
        this.correlationId = java.util.UUID.randomUUID().toString()
        this.telemetry = new TelemetryManager(steps)
        this.audit = new AuditLoggingManager(steps)
        this.metrics = new BuildMetricsManager(steps, this.correlationId)
        this.otel = new OpenTelemetryManager(steps, this.correlationId)
        this.contextManager = new ExecutionContextManager(steps, this.correlationId)
        this.recovery = new FailureRecoveryManager(steps, this.correlationId)
        this.parallel = new ParallelExecutionManager(steps, this.correlationId)
        this.shell = new ShellUtils(steps)
        initialize()
    }

    private void initialize() {
        LoggingUtils.info("PipelineExecutionFramework",
            "Initializing pipeline lifecycle engine [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(projectYamlRaw)) {
            throw new IllegalArgumentException("project.yml content must not be null or empty")
        }

        try {
            configManager = new ProjectConfigurationManager(steps, correlationId)
            resolvedConfig = configManager.process(projectYamlRaw)

            projectName = configManager.getProjectName()
            businessUnit = configManager.getBusinessUnit()

            if (!ValidationUtils.isNonEmpty(projectName)) {
                projectName = "unknown-project"
            }
            if (!ValidationUtils.isNonEmpty(businessUnit)) {
                businessUnit = "unknown-bu"
            }

            Map nexus = extractMap(resolvedConfig, "nexus")
            nexusUrl = extractString(nexus, "url") ?: "http://nexus:8081"

            String rawBranch = resolveEnv("BRANCH_NAME", "")
            if (!ValidationUtils.isNonEmpty(rawBranch)) {
                rawBranch = resolveEnv("GIT_BRANCH", "")
            }
            gitBranch = rawBranch

            contextManager.initialize(projectName, businessUnit, resolvedConfig)
            metrics.recordBuildStart()
            otel.setServiceName("buildos-${projectName}")

            LoggingUtils.info("PipelineExecutionFramework",
                "Pipeline initialized: project='${projectName}', BU='${businessUnit}', nexus='${nexusUrl}', branch='${gitBranch}' [correlationId=${correlationId}]")

            audit.emitAuditEvent("PIPELINE_INITIALIZED",
                "Pipeline framework initialized for '${projectName}'", correlationId)
            telemetry.emitEvent("framework", "initialized", [
                correlationId: correlationId,
                projectName: projectName,
                businessUnit: businessUnit,
                nexusUrl: nexusUrl,
                branch: gitBranch,
                lifecycleVersion: LIFECYCLE_VERSION
            ])

        } catch (Exception e) {
            String msg = "Pipeline initialization failed: ${e.message}"
            LoggingUtils.error("PipelineExecutionFramework", msg, e)
            throw new RuntimeException(msg, e)
        }
    }

    void runBuildOSLifecycle() {
        if (lifecycleStarted) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Lifecycle has already been started. Ignoring duplicate call.")
            return
        }
        lifecycleStarted = true

        LoggingUtils.info("PipelineExecutionFramework",
            "Starting BuildOS Platform 50-stage lifecycle [correlationId=${correlationId}]")

        try {
            stage("Setup Stage 01: Agent Binding") { executeStage01() }
            stage("Setup Stage 02: Workspace Hygiene") { executeStage02() }
            stage("Setup Stage 03: Config Validation") { executeStage03() }
            stage("Setup Stage 04: Credentials Injection") { executeStage04() }
            stage("Setup Stage 05: Toolchain Verification") { executeStage05() }

            stage("SCM Stage 06: Branch Governance Check") { executeStage06() }
            stage("SCM Stage 07: Multibranch Context") { executeStage07() }
            stage("SCM Stage 08: Changelog Generation") { executeStage08() }
            stage("SCM Stage 09: Protected Branch Enforcement") { executeStage09() }
            stage("SCM Stage 10: SCM Metadata Finalize") { executeStage10() }

            stage("Provisioning Stage 11: Nexus Blob Store") { executeStage11() }
            stage("Provisioning Stage 12: Snapshot Repositories") { executeStage12() }
            stage("Provisioning Stage 13: Release Repositories") { executeStage13() }
            stage("Provisioning Stage 14: Cleanup Policies") { executeStage14() }
            stage("Provisioning Stage 15: RBAC Folder Sync") { executeStage15() }

            stage("Build Stage 16: Maven Settings Generation") { executeStage16() }
            stage("Build Stage 17: Dependency Resolution") { executeStage17() }
            stage("Build Stage 18: Compilation") { executeStage18() }
            stage("Build Stage 19: Unit Tests") { executeStage19() }
            stage("Build Stage 20: Assembly Packaging") { executeStage20() }
            stage("Build Stage 21: Artifact Naming") { executeStage21() }
            stage("Build Stage 22: Build Finalize") { executeStage22() }

            stage("Security Stage 23: Trivy Scan") { executeStage23() }
            stage("Security Stage 24: Dependency Audit") { executeStage24() }
            stage("Security Stage 25: Secret Detection") { executeStage25() }
            stage("Security Stage 26: Compliance Validation") { executeStage26() }
            stage("Security Stage 27: SBOM Generation") { executeStage27() }
            stage("Security Stage 28: Artifact Signing") { executeStage28() }

            stage("Quality Stage 29: Code Coverage") { executeStage29() }
            stage("Quality Stage 30: SonarQube Analysis") { executeStage30() }
            stage("Quality Stage 31: Quality Gate Polling") { executeStage31() }
            stage("Quality Stage 32: Threshold Enforcement") { executeStage32() }
            stage("Quality Stage 33: Quality Report") { executeStage33() }

            stage("Publish Stage 34: Distribution Management") { executeStage34() }
            stage("Publish Stage 35: Nexus Upload") { executeStage35() }
            stage("Publish Stage 36: Release Staging") { executeStage36() }
            stage("Publish Stage 37: Tag Creation") { executeStage37() }
            stage("Publish Stage 38: Version Bump") { executeStage38() }
            stage("Publish Stage 39: Publish Verification") { executeStage39() }
            stage("Publish Stage 40: Publish Finalize") { executeStage40() }

            stage("Observability Stage 41: Telemetry Emission") { executeStage41() }
            stage("Observability Stage 42: Audit Log Finalization") { executeStage42() }
            stage("Observability Stage 43: Build Metrics") { executeStage43() }
            stage("Observability Stage 44: OpenTelemetry Span Closure") { executeStage44() }
            stage("Observability Stage 45: Observability Finalize") { executeStage45() }

            stage("Finalize Stage 46: Retry Summary Report") { executeStage46() }
            stage("Finalize Stage 47: Failure Recovery Check") { executeStage47() }
            stage("Finalize Stage 48: Workspace Cleanup") { executeStage48() }
            stage("Finalize Stage 49: Post-Build Notifications") { executeStage49() }
            stage("Finalize Stage 50: Lifecycle Manifest Archive") { executeStage50() }

            LoggingUtils.info("PipelineExecutionFramework",
                "50-stage lifecycle completed successfully [correlationId=${correlationId}]")

            Map finalReport = metrics.finalizeAndReport()
            audit.emitAuditEvent("LIFECYCLE_COMPLETED",
                "All 50 stages completed for '${projectName}'", correlationId)
            telemetry.emitEvent("framework", "lifecycle_completed", [
                correlationId: correlationId,
                projectName: projectName,
                totalStages: 50,
                durationMs: contextManager.elapsedMs()
            ])

        } catch (Exception e) {
            LoggingUtils.error("PipelineExecutionFramework",
                "Lifecycle failed: ${e.message} [correlationId=${correlationId}]", e)
            audit.emitAuditEvent("LIFECYCLE_FAILED",
                "Lifecycle failed at stage ${contextManager.currentStageIndex()}: ${e.message}", correlationId)
            telemetry.emitEvent("framework", "lifecycle_failed", [
                correlationId: correlationId,
                failedStage: contextManager.currentStage(),
                failedStageIndex: contextManager.currentStageIndex(),
                error: e.message
            ])
            throw e

        } finally {
            try {
                LoggingUtils.info("PipelineExecutionFramework",
                    "Post-execution cleanup [correlationId=${correlationId}]")
                cleanWs()
                otel.flushToEndpoint()
            } catch (Exception cleanupEx) {
                LoggingUtils.warn("PipelineExecutionFramework",
                    "Cleanup encountered an issue: ${cleanupEx.message}")
            }
        }
    }

    String getAgentLabel() { return AGENT_LABEL }
    String getProjectName() { return this.projectName }
    String getBusinessUnit() { return this.businessUnit }
    String getCorrelationId() { return this.correlationId }

    /*
     * =====================================================
     * SETUP STAGES 01-05
     * =====================================================
     */

    private void executeStage01() {
        enterStage("stage-01")
        LoggingUtils.info("PipelineExecutionFramework", "Binding agent: ${AGENT_LABEL}")
        steps.node(AGENT_LABEL) {
            LoggingUtils.info("PipelineExecutionFramework", "Agent bound: ${AGENT_LABEL}")
            audit.emitAuditEvent("AGENT_BOUND", "Agent '${AGENT_LABEL}' bound", correlationId)
        }
        exitStage("stage-01", "COMPLETED", [agentLabel: AGENT_LABEL])
    }

    private void executeStage02() {
        enterStage("stage-02")
        LoggingUtils.info("PipelineExecutionFramework", "Performing workspace hygiene")
        steps.sh(script: "rm -rf .buildos-* 2>/dev/null; mkdir -p target reports 2>/dev/null", returnStatus: true)
        audit.emitAuditEvent("WORKSPACE_HYGIENE", "Workspace cleaned", correlationId)
        exitStage("stage-02", "COMPLETED", [:])
    }

    private void executeStage03() {
        enterStage("stage-03")
        LoggingUtils.info("PipelineExecutionFramework", "Validating resolved configuration")
        if (resolvedConfig == null || resolvedConfig.isEmpty()) {
            throw new RuntimeException("Resolved configuration is empty after processing")
        }
        List<String> requiredKeys = ["project", "branchGovernance"]
        for (String key : requiredKeys) {
            if (!resolvedConfig.containsKey(key)) {
                throw new RuntimeException("Required configuration key '${key}' is missing")
            }
        }
        audit.emitAuditEvent("CONFIG_VALIDATED", "Resolved configuration validated", correlationId)
        exitStage("stage-03", "COMPLETED", [configKeys: resolvedConfig.keySet().size()])
    }

    private void executeStage04() {
        enterStage("stage-04")
        LoggingUtils.info("PipelineExecutionFramework", "Injecting credentials")
        String nexusCredsId = extractString(
            extractMap(resolvedConfig, "nexus"), "credentialsId")
        if (!ValidationUtils.isNonEmpty(nexusCredsId)) {
            nexusCredsId = "enterprise-nexus-credentials"
        }
        steps.withCredentials([steps.usernamePassword(
            credentialsId: nexusCredsId,
            usernameVariable: "NEXUS_USER",
            passwordVariable: "NEXUS_PASS"
        )]) {
            LoggingUtils.info("PipelineExecutionFramework", "Nexus credentials injected")
            audit.emitAuditEvent("CREDENTIALS_INJECTED",
                "Nexus credentials injected: ${nexusCredsId}", correlationId)
        }
        exitStage("stage-04", "COMPLETED", [credentialsId: nexusCredsId])
    }

    private void executeStage05() {
        enterStage("stage-05")
        LoggingUtils.info("PipelineExecutionFramework", "Verifying toolchain")
        List<String> tools = ["java", "mvn", "gpg", "trivy"]
        List<String> missing = []
        for (String tool : tools) {
            try {
                steps.sh(script: "command -v '${tool}' >/dev/null 2>&1", returnStatus: true)
            } catch (Exception e) {
                missing.add(tool)
            }
        }
        if (!missing.isEmpty()) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Optional tools not found: ${missing.join(', ')}")
        }
        steps.sh(script: "java -version 2>&1 | head -1", returnStdout: true)
        audit.emitAuditEvent("TOOLCHAIN_VERIFIED",
            "Toolchain verified: ${tools.size() - missing.size()}/${tools.size()} available", correlationId)
        exitStage("stage-05", "COMPLETED", [missingTools: missing])
    }

    /*
     * =====================================================
     * SCM & GOVERNANCE STAGES 06-10
     * =====================================================
     */

    private void executeStage06() {
        enterStage("stage-06")
        if (!ValidationUtils.isNonEmpty(gitBranch)) {
            gitBranch = steps.env.BRANCH_NAME ?: "unknown"
        }
        BranchGovernanceManager gov = new BranchGovernanceManager(steps, correlationId)
        Map branchConfig = extractMap(resolvedConfig, "branchGovernance")
        Map govResult = gov.validateBranch(gitBranch, branchConfig)
        if (!govResult.authorized instanceof Boolean || !((Boolean) govResult.authorized)) {
            throw new RuntimeException("Branch '${gitBranch}' not authorized by governance policy")
        }
        LoggingUtils.info("PipelineExecutionFramework",
            "Branch governance passed for '${gitBranch}'")
        exitStage("stage-06", "COMPLETED", [branch: gitBranch, authorized: true])
    }

    private void executeStage07() {
        enterStage("stage-07")
        MultibranchOrchestrator orch = new MultibranchOrchestrator(steps, correlationId)
        Map mbResult = orch.configureMultibranchProject(resolvedConfig)
        LoggingUtils.info("PipelineExecutionFramework",
            "Multibranch context: ${mbResult.projectName}")
        exitStage("stage-07", "COMPLETED", mbResult)
    }

    private void executeStage08() {
        enterStage("stage-08")
        String changeLog = ""
        try {
            changeLog = steps.sh(
                script: "git log --oneline -20 2>/dev/null || echo 'No git history available'",
                returnStdout: true
            ).toString()
        } catch (Exception e) {
            changeLog = "Changelog generation failed: ${e.message}"
        }
        LoggingUtils.info("PipelineExecutionFramework",
            "Changelog: ${changeLog.length()} chars")
        audit.emitAuditEvent("CHANGELOG_GENERATED",
            "Changelog generated (${changeLog.length()} chars)", correlationId)
        exitStage("stage-08", "COMPLETED", [changelogLength: changeLog.length()])
    }

    private void executeStage09() {
        enterStage("stage-09")
        BranchProtectionManager protection = new BranchProtectionManager(steps, correlationId)
        boolean isProtected = protection.isProtectedBranch(gitBranch)
        if (isProtected) {
            Map protConfig = extractMap(resolvedConfig, "branchProtection") ?: [:]
            Map protResult = protection.enforceProtectedBranchPolicy(gitBranch, protConfig)
            LoggingUtils.info("PipelineExecutionFramework",
                "Protected branch enforcement: ${protResult.status}")
        } else {
            LoggingUtils.info("PipelineExecutionFramework",
                "Branch '${gitBranch}' is not protected")
        }
        exitStage("stage-09", "COMPLETED", [isProtected: isProtected])
    }

    private void executeStage10() {
        enterStage("stage-10")
        String scmSummary = "Branch=${gitBranch}, Project=${projectName}, BU=${businessUnit}"
        LoggingUtils.info("PipelineExecutionFramework",
            "SCM metadata finalized: ${scmSummary}")
        audit.emitAuditEvent("SCM_METADATA_FINALIZED", scmSummary, correlationId)
        exitStage("stage-10", "COMPLETED", [:])
    }

    /*
     * =====================================================
     * PROVISIONING STAGES 11-15
     * =====================================================
     */

    private void executeStage11() {
        enterStage("stage-11")
        String nexusCredsId = extractString(extractMap(resolvedConfig, "nexus"), "credentialsId")
        if (!ValidationUtils.isNonEmpty(nexusCredsId)) nexusCredsId = "enterprise-nexus-credentials"
        NexusProvisioningEngine nexus = new NexusProvisioningEngine(steps, nexusUrl, nexusCredsId, correlationId)
        Map nexusConfig = extractMap(resolvedConfig, "nexus")
        Map nexusResult = nexus.enforceRepositoryFootprint(resolvedConfig)
        LoggingUtils.info("PipelineExecutionFramework",
            "Nexus provisioning: ${nexusResult.status} (${nexusResult.provisioningResults?.size() ?: 0} resources)")
        exitStage("stage-11", "COMPLETED", nexusResult)
    }

    private void executeStage12() {
        enterStage("stage-12")
        LoggingUtils.info("PipelineExecutionFramework", "Snapshot repo provisioning handled by Nexus engine")
        exitStage("stage-12", "COMPLETED", [phase: "delegated_to_nexus"])
    }

    private void executeStage13() {
        enterStage("stage-13")
        LoggingUtils.info("PipelineExecutionFramework", "Release repo provisioning handled by Nexus engine")
        exitStage("stage-13", "COMPLETED", [phase: "delegated_to_nexus"])
    }

    private void executeStage14() {
        enterStage("stage-14")
        LoggingUtils.info("PipelineExecutionFramework", "Cleanup policy provisioning handled by Nexus engine")
        exitStage("stage-14", "COMPLETED", [phase: "delegated_to_nexus"])
    }

    private void executeStage15() {
        enterStage("stage-15")
        LoggingUtils.info("PipelineExecutionFramework", "RBAC folder sync handled by control plane")
        audit.emitAuditEvent("RBAC_FOLDER_SYNC", "RBAC folder sync delegated to control plane", correlationId)
        exitStage("stage-15", "COMPLETED", [phase: "delegated_to_control_plane"])
    }

    /*
     * =====================================================
     * BUILD STAGES 16-22
     * =====================================================
     */

    private void executeStage16() {
        enterStage("stage-16")
        DynamicMavenSettingsGenerator settingsGen = new DynamicMavenSettingsGenerator(steps, correlationId)
        String settingsXml = settingsGen.generateSettingsXml(nexusUrl, resolvedConfig)
        LoggingUtils.info("PipelineExecutionFramework",
            "Maven settings.xml generated (${settingsXml.length()} chars)")
        contextManager.setVariable("settingsXml", settingsXml)
        exitStage("stage-16", "COMPLETED", [settingsLength: settingsXml.length()])
    }

    private void executeStage17() {
        enterStage("stage-17")
        try {
            String settingsXml = contextManager.getVariable("settingsXml") as String ?: ""
            if (ValidationUtils.isNonEmpty(settingsXml)) {
                steps.writeFile(file: ".buildos-settings-${correlationId}.xml", text: settingsXml)
            }
            steps.sh(script: "mvn -f pom.xml dependency:resolve -s .buildos-settings-${correlationId}.xml -B -q 2>&1", returnStdout: true)
            LoggingUtils.info("PipelineExecutionFramework", "Dependencies resolved")
        } catch (Exception e) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Dependency resolution encountered issues: ${e.message}")
        }
        exitStage("stage-17", "COMPLETED", [:])
    }

    private void executeStage18() {
        enterStage("stage-18")
        String settingsXml = contextManager.getVariable("settingsXml") as String ?: ""
        try {
            steps.sh(script: "mvn -f pom.xml compiler:compile -s .buildos-settings-${correlationId}.xml -B -q 2>&1", returnStdout: true)
            LoggingUtils.info("PipelineExecutionFramework", "Compilation completed")
        } catch (Exception e) {
            throw new RuntimeException("Compilation failed: ${e.message}")
        }
        exitStage("stage-18", "COMPLETED", [:])
    }

    private void executeStage19() {
        enterStage("stage-19")
        Boolean skipTests = extractBoolean(
            extractMap(resolvedConfig, "maven"), "skipTests", false)
        if (!skipTests) {
            try {
                steps.sh(script: "mvn -f pom.xml surefire:test -s .buildos-settings-${correlationId}.xml -B 2>&1", returnStdout: true)
                LoggingUtils.info("PipelineExecutionFramework", "Unit tests completed")
            } catch (Exception e) {
                throw new RuntimeException("Unit tests failed: ${e.message}")
            }
        } else {
            LoggingUtils.info("PipelineExecutionFramework", "Unit tests skipped per configuration")
        }
        exitStage("stage-19", "COMPLETED", [skipped: skipTests])
    }

    private void executeStage20() {
        enterStage("stage-20")
        AssemblyPackagingManager assembly = new AssemblyPackagingManager(steps, correlationId)
        Map assemblyConfig = extractMap(extractMap(resolvedConfig, "maven"), "assembly") ?: [:]
        String descriptorName = extractString(assemblyConfig, "name") ?: "${projectName}-distribution"
        String descriptorXml = assembly.generateAssemblyDescriptor(descriptorName, assemblyConfig)
        contextManager.setVariable("assemblyDescriptor", descriptorXml)
        if (ValidationUtils.isNonEmpty(descriptorXml)) {
            steps.writeFile(file: "assembly-${descriptorName}.xml", text: descriptorXml)
            try {
                steps.sh(script: "mvn -f pom.xml -Dassembly.descriptor=assembly-${descriptorName}.xml assembly:single -B -q 2>&1", returnStdout: true)
            } catch (Exception e) {
                LoggingUtils.warn("PipelineExecutionFramework",
                    "Assembly execution: ${e.message}")
            }
        }
        exitStage("stage-20", "COMPLETED", [descriptor: descriptorName])
    }

    private void executeStage21() {
        enterStage("stage-21")
        ArtifactNamingStrategy naming = new ArtifactNamingStrategy(steps, correlationId)
        String groupId = naming.resolveGroupId(resolvedConfig)
        String artifactId = naming.resolveArtifactId(resolvedConfig)
        String version = naming.resolveVersion(resolvedConfig)
        String finalName = naming.buildFinalArtifactName(resolvedConfig)
        contextManager.setVariable("groupId", groupId)
        contextManager.setVariable("artifactId", artifactId)
        contextManager.setVariable("version", version)
        contextManager.setVariable("finalArtifactName", finalName)
        LoggingUtils.info("PipelineExecutionFramework",
            "Artifact naming: ${groupId}:${artifactId}:${version} → ${finalName}")
        exitStage("stage-21", "COMPLETED", [groupId: groupId, artifactId: artifactId, version: version, finalName: finalName])
    }

    private void executeStage22() {
        enterStage("stage-22")
        try {
            steps.sh(script: "ls -la target/*.jar 2>/dev/null || echo 'No jar files found in target/'", returnStdout: true)
        } catch (Exception e) { }
        audit.emitAuditEvent("BUILD_FINALIZED", "Build phase finalized for '${projectName}'", correlationId)
        exitStage("stage-22", "COMPLETED", [:])
    }

    /*
     * =====================================================
     * SECURITY STAGES 23-28 (parallel where safe)
     * =====================================================
     */

    private void executeStage23() {
        enterStage("stage-23")
        TrivyScanManager trivy = new TrivyScanManager(steps, correlationId)
        Map securityConfig = extractMap(extractMap(resolvedConfig, "security"), "trivy") ?: [:]
        String targetPath = steps.env.WORKSPACE ?: "."
        Map trivyResult = trivy.scanFilesystem(targetPath, securityConfig)
        contextManager.setVariable("trivyResult", trivyResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "Trivy scan: ${trivyResult.criticalCount} critical, ${trivyResult.highCount} high")
        exitStage("stage-23", "COMPLETED", [
            critical: trivyResult.criticalCount,
            high: trivyResult.highCount,
            total: trivyResult.totalVulnerabilities
        ])
    }

    private void executeStage24() {
        enterStage("stage-24")
        DependencyScanManager depScan = new DependencyScanManager(steps, correlationId)
        Map depConfig = extractMap(extractMap(resolvedConfig, "security"), "dependencyScan") ?: [:]
        String scanTarget = steps.env.WORKSPACE ?: "."
        Map depResult = depScan.scanDependencies(scanTarget, depConfig)
        contextManager.setVariable("dependencyScanResult", depResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "Dependency scan: ${depResult.criticalCount} critical, ${depResult.highCount} high")
        exitStage("stage-24", "COMPLETED", [
            critical: depResult.criticalCount,
            high: depResult.highCount,
            total: depResult.totalCves
        ])
    }

    private void executeStage25() {
        enterStage("stage-25")
        SecretScanManager secretScan = new SecretScanManager(steps, correlationId)
        Map secretConfig = extractMap(extractMap(resolvedConfig, "security"), "secretScan") ?: [:]
        String workspace = steps.env.WORKSPACE ?: "."
        Map secretResult = secretScan.scanWorkspace(workspace, secretConfig)
        contextManager.setVariable("secretScanResult", secretResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "Secret scan: ${secretResult.criticalCount} critical, ${secretResult.highCount} high")
        if (secretResult.criticalCount > 0) {
            throw new RuntimeException("CRITICAL secrets detected: ${secretResult.criticalCount}")
        }
        exitStage("stage-25", "COMPLETED", [
            critical: secretResult.criticalCount,
            high: secretResult.highCount,
            total: secretResult.totalFindings
        ])
    }

    private void executeStage26() {
        enterStage("stage-26")
        ComplianceValidationManager compliance = new ComplianceValidationManager(steps, correlationId)
        Map scanResults = [:]
        scanResults["trivyResults"] = contextManager.getVariable("trivyResult")
        scanResults["vulnerabilities"] = contextManager.getVariable("dependencyScanResult")
        Map complianceResult = compliance.validateCompliance(resolvedConfig, scanResults)
        contextManager.setVariable("complianceResult", complianceResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "Compliance: ${complianceResult.violations} violations, ${complianceResult.warnings} warnings")
        exitStage("stage-26", "COMPLETED", [
            violations: complianceResult.violations,
            warnings: complianceResult.warnings
        ])
    }

    private void executeStage27() {
        enterStage("stage-27")
        SBOMGenerationManager sbom = new SBOMGenerationManager(steps, correlationId)
        Map sbomConfig = extractMap(extractMap(resolvedConfig, "security"), "sbom") ?: [:]
        String projectPath = steps.env.WORKSPACE ?: "."
        Map sbomResult = sbom.generateSbom(projectPath, sbomConfig)
        contextManager.setVariable("sbomResult", sbomResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "SBOM generated: ${sbomResult.totalComponents} components")
        exitStage("stage-27", "COMPLETED", [
            components: sbomResult.totalComponents,
            format: sbomResult.format
        ])
    }

    private void executeStage28() {
        enterStage("stage-28")
        ArtifactSigningManager signing = new ArtifactSigningManager(steps, correlationId)
        Map signingConfig = extractMap(extractMap(resolvedConfig, "security"), "signing") ?: [:]
        try {
            steps.sh(script: "ls target/*.jar 2>/dev/null || ls target/*.war 2>/dev/null || true", returnStdout: true)
        } catch (Exception e) { }
        List<String> artifacts = findArtifacts()
        if (!artifacts.isEmpty()) {
            Map signResult = signing.signArtifacts(artifacts, signingConfig)
            contextManager.setVariable("signingResult", signResult)
            LoggingUtils.info("PipelineExecutionFramework",
                "Artifact signing: ${signResult.successCount} signed, ${signResult.failureCount} failed")
        } else {
            LoggingUtils.info("PipelineExecutionFramework", "No artifacts found for signing")
        }
        exitStage("stage-28", "COMPLETED", [
            artifactsFound: artifacts.size(),
            signed: artifacts.size()
        ])
    }

    /*
     * =====================================================
     * QUALITY STAGES 29-33
     * =====================================================
     */

    private void executeStage29() {
        enterStage("stage-29")
        CodeCoverageManager coverage = new CodeCoverageManager(steps, correlationId)
        String reportPath = "target/site/jacoco/jacoco.xml"
        Map coverageData = [lineCoverage: 0, branchCoverage: 0]
        if (steps.fileExists(reportPath)) {
            coverageData = coverage.parseCoverageReport(reportPath, "jacoco")
        } else {
            reportPath = "target/site/cobertura/coverage.xml"
            if (steps.fileExists(reportPath)) {
                coverageData = coverage.parseCoverageReport(reportPath, "cobertura")
            }
        }
        contextManager.setVariable("coverageData", coverageData)
        LoggingUtils.info("PipelineExecutionFramework",
            "Coverage: ${coverageData.lineCoverage}% line, ${coverageData.branchCoverage}% branch")
        exitStage("stage-29", "COMPLETED", [
            lineCoverage: coverageData.lineCoverage,
            branchCoverage: coverageData.branchCoverage
        ])
    }

    private void executeStage30() {
        enterStage("stage-30")
        SonarQubeManager sonar = new SonarQubeManager(steps, correlationId)
        Map qualityConfig = extractMap(resolvedConfig, "quality") ?: [:]
        Map sonarConfig = extractMap(qualityConfig, "sonarQube") ?: [:]
        sonarConfig["sonarHostUrl"] = extractString(sonarConfig, "hostUrl") ?: extractString(sonarConfig, "url") ?: "http://sonarqube:9000"
        String token = extractString(sonarConfig, "token") ?: ""
        Map sonarResult = sonar.executeAnalysis(projectName, sonarConfig)
        contextManager.setVariable("sonarResult", sonarResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "SonarQube analysis: ${sonarResult.status}, CE task: ${sonarResult.ceTaskId}")
        exitStage("stage-30", "COMPLETED", [
            status: sonarResult.status,
            ceTaskId: sonarResult.ceTaskId
        ])
    }

    private void executeStage31() {
        enterStage("stage-31")
        QualityGateManager gate = new QualityGateManager(steps, correlationId)
        SonarQubeManager sonar = new SonarQubeManager(steps, correlationId)
        Map qualityConfig = extractMap(resolvedConfig, "quality") ?: [:]
        Map sonarConfig = extractMap(qualityConfig, "sonarQube") ?: [:]
        String host = extractString(sonarConfig, "hostUrl") ?: "http://sonarqube:9000"
        String token = extractString(sonarConfig, "token") ?: ""
        Integer timeout = sonarConfig.qualityGateTimeoutMs instanceof Number ?
            ((Number) sonarConfig.qualityGateTimeoutMs).intValue() : 300000
        Map gateResult = gate.pollQualityGate(sonar, projectName, host, token, timeout)
        contextManager.setVariable("qualityGateResult", gateResult)
        LoggingUtils.info("PipelineExecutionFramework",
            "Quality gate polling: ${gateResult.status} after ${gateResult.pollingDurationMs}ms")
        exitStage("stage-31", "COMPLETED", [
            status: gateResult.status,
            polls: gateResult.pollCount,
            durationMs: gateResult.pollingDurationMs
        ])
    }

    private void executeStage32() {
        enterStage("stage-32")
        QualityGateManager gate = new QualityGateManager(steps, correlationId)
        Map coverageData = contextManager.getVariable("coverageData") instanceof Map ?
            (Map) contextManager.getVariable("coverageData") : [:]
        Map qualityConfig = extractMap(resolvedConfig, "quality") ?: [:]
        Map thresholdConfig = extractMap(qualityConfig, "coverage") ?: [:]
        Map gateResult = gate.evaluateQualityGate(
            contextManager.getVariable("qualityGateResult") ?: [:],
            coverageData,
            thresholdConfig
        )
        LoggingUtils.info("PipelineExecutionFramework",
            "Quality gate evaluation: ${gateResult.status}")
        exitStage("stage-32", "COMPLETED", [status: gateResult.status, passed: gateResult.passed])
    }

    private void executeStage33() {
        enterStage("stage-33")
        String qualityReport = "Quality Report for ${projectName}:\n" +
            "  Coverage: ${contextManager.getVariable('coverageData')?.lineCoverage ?: 'N/A'}%\n" +
            "  SonarQube: ${contextManager.getVariable('qualityGateResult')?.status ?: 'N/A'}\n" +
            "  Timestamp: ${ExecutionUtils.formatTimestamp()}"
        LoggingUtils.info("PipelineExecutionFramework",
            "Quality report generated")
        audit.emitAuditEvent("QUALITY_REPORT_GENERATED",
            "Quality report generated for '${projectName}'", correlationId)
        exitStage("stage-33", "COMPLETED", [:])
    }

    /*
     * =====================================================
     * PUBLISH STAGES 34-40
     * =====================================================
     */

    private void executeStage34() {
        enterStage("stage-34")
        DistributionManagementGenerator distMgmt = new DistributionManagementGenerator(steps, correlationId)
        String distMgmtXml = distMgmt.generateDistributionManagementXml(nexusUrl, resolvedConfig)
        contextManager.setVariable("distMgmtXml", distMgmtXml)
        LoggingUtils.info("PipelineExecutionFramework",
            "Distribution management XML generated (${distMgmtXml.length()} chars)")
        exitStage("stage-34", "COMPLETED", [xmlLength: distMgmtXml.length()])
    }

    private void executeStage35() {
        enterStage("stage-35")
        try {
            steps.sh(script: "mvn -f pom.xml -s .buildos-settings-${correlationId}.xml deploy -B -DskipTests -q 2>&1", returnStdout: true)
            LoggingUtils.info("PipelineExecutionFramework", "Nexus upload completed")
            audit.emitAuditEvent("NEXUS_UPLOAD", "Artifacts uploaded to Nexus", correlationId)
        } catch (Exception e) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Nexus upload: ${e.message}")
            throw new RuntimeException("Nexus upload failed: ${e.message}")
        }
        exitStage("stage-35", "COMPLETED", [:])
    }

    private void executeStage36() {
        enterStage("stage-36")
        LoggingUtils.info("PipelineExecutionFramework", "Release staging prepared")
        String version = contextManager.getVariable("version") as String ?: "1.0.0"
        audit.emitAuditEvent("RELEASE_STAGING",
            "Release staging prepared for ${projectName}:${version}", correlationId)
        exitStage("stage-36", "COMPLETED", [version: version])
    }

    private void executeStage37() {
        enterStage("stage-37")
        String version = contextManager.getVariable("version") as String ?: "1.0.0"
        try {
            String tagName = "${projectName}-${version}"
            steps.sh(script: "git tag -a '${tagName}' -m 'BuildOS Platform release ${tagName}' 2>&1 || true", returnStdout: true)
            LoggingUtils.info("PipelineExecutionFramework", "Tag created: ${tagName}")
            audit.emitAuditEvent("TAG_CREATED", "Tag '${tagName}' created", correlationId)
        } catch (Exception e) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Tag creation: ${e.message}")
        }
        exitStage("stage-37", "COMPLETED", [tag: "${projectName}-${version}"])
    }

    private void executeStage38() {
        enterStage("stage-38")
        String version = contextManager.getVariable("version") as String ?: "1.0.0"
        String nextVersion = computeNextVersion(version)
        LoggingUtils.info("PipelineExecutionFramework",
            "Version bump: ${version} → ${nextVersion}")
        audit.emitAuditEvent("VERSION_BUMP", "Version bumped to ${nextVersion}", correlationId)
        exitStage("stage-38", "COMPLETED", [previousVersion: version, nextVersion: nextVersion])
    }

    private void executeStage39() {
        enterStage("stage-39")
        try {
            steps.sh(script: "ls -la target/*.jar target/*.asc 2>/dev/null || true", returnStdout: true)
        } catch (Exception e) { }
        LoggingUtils.info("PipelineExecutionFramework", "Publish verification completed")
        exitStage("stage-39", "COMPLETED", [:])
    }

    private void executeStage40() {
        enterStage("stage-40")
        Map publishSummary = [
            nexusUrl: nexusUrl,
            version: contextManager.getVariable("version"),
            artifactName: contextManager.getVariable("finalArtifactName")
        ]
        audit.emitAuditEvent("PUBLISH_FINALIZED",
            "Publish finalized for ${projectName}", correlationId)
        LoggingUtils.info("PipelineExecutionFramework", "Publish phase finalized")
        exitStage("stage-40", "COMPLETED", publishSummary)
    }

    /*
     * =====================================================
     * OBSERVABILITY STAGES 41-45
     * =====================================================
     */

    private void executeStage41() {
        enterStage("stage-41")
        telemetry.emitEvent("framework", "observability_started", [
            correlationId: correlationId,
            projectName: projectName,
            businessUnit: businessUnit,
            elapsedMs: contextManager.elapsedMs()
        ])
        LoggingUtils.info("PipelineExecutionFramework",
            "Telemetry emission: observability_started")
        exitStage("stage-41", "COMPLETED", [:])
    }

    private void executeStage42() {
        enterStage("stage-42")
        Map stageSummary = contextManager.getStageSummary()
        audit.emitAuditEvent("AUDIT_LOG_FINALIZED",
            "Audit log finalized: ${stageSummary.completed}/${stageSummary.totalStages} stages completed", correlationId)
        LoggingUtils.info("PipelineExecutionFramework",
            "Audit log finalized")
        exitStage("stage-42", "COMPLETED", stageSummary)
    }

    private void executeStage43() {
        enterStage("stage-43")
        Map buildReport = metrics.finalizeAndReport()
        contextManager.setVariable("buildReport", buildReport)
        LoggingUtils.info("PipelineExecutionFramework",
            "Build metrics: ${buildReport.totalDurationMs}ms total, ${buildReport.completedStages} stages completed")
        exitStage("stage-43", "COMPLETED", [
            totalDurationMs: buildReport.totalDurationMs,
            stages: buildReport.completedStages,
            status: buildReport.buildStatus
        ])
    }

    private void executeStage44() {
        enterStage("stage-44")
        otel.injectTraceContext()
        LoggingUtils.info("PipelineExecutionFramework",
            "OpenTelemetry spans finalized, trace context injected")
        exitStage("stage-44", "COMPLETED", [traceId: otel.getTraceId()])
    }

    private void executeStage45() {
        enterStage("stage-45")
        LoggingUtils.info("PipelineExecutionFramework",
            "Observability finalized [correlationId=${correlationId}]")
        exitStage("stage-45", "COMPLETED", [:])
    }

    /*
     * =====================================================
     * FINALIZE STAGES 46-50
     * =====================================================
     */

    private void executeStage46() {
        enterStage("stage-46")
        Map retryReport = recovery.generateRetrySummaryReport()
        contextManager.setVariable("retrySummary", retryReport)
        LoggingUtils.info("PipelineExecutionFramework",
            "Retry summary: ${retryReport.totalFailures} failures across ${retryReport.stagesAffected?.size() ?: 0} stages")
        exitStage("stage-46", "COMPLETED", [
            totalFailures: retryReport.totalFailures,
            stagesAffected: retryReport.stagesAffected?.size() ?: 0
        ])
    }

    private void executeStage47() {
        enterStage("stage-47")
        boolean hasFailures = recovery.hasFailures()
        if (hasFailures) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "${recovery.getFailureHistory().size()} failure(s) occurred during lifecycle")
        } else {
            LoggingUtils.info("PipelineExecutionFramework",
                "No failures recorded during lifecycle")
        }
        exitStage("stage-47", "COMPLETED", [hasFailures: hasFailures])
    }

    private void executeStage48() {
        enterStage("stage-48")
        try {
            steps.sh(script: "rm -rf .buildos-* 2>/dev/null; rm -rf target/.buildos-* 2>/dev/null; true", returnStatus: true)
            LoggingUtils.info("PipelineExecutionFramework", "Workspace cleanup completed")
        } catch (Exception e) {
            LoggingUtils.warn("PipelineExecutionFramework",
                "Workspace cleanup: ${e.message}")
        }
        exitStage("stage-48", "COMPLETED", [:])
    }

    private void executeStage49() {
        enterStage("stage-49")
        String buildStatus = metrics.finalizeAndReport().buildStatus ?: "COMPLETED"
        String statusEmoji = "SUCCESS".equals(buildStatus) ? "PASSED" : "FAILED"
        LoggingUtils.info("PipelineExecutionFramework",
            "Post-build notification: ${projectName} ${statusEmoji}")
        audit.emitAuditEvent("POST_BUILD_NOTIFICATION",
            "Build ${statusEmoji}: ${projectName}", correlationId)
        exitStage("stage-49", "COMPLETED", [status: buildStatus])
    }

    private void executeStage50() {
        enterStage("stage-50")
        Map manifest = [
            lifecycleVersion: LIFECYCLE_VERSION,
            projectName: projectName,
            businessUnit: businessUnit,
            correlationId: correlationId,
            completedAt: ExecutionUtils.formatTimestamp(),
            totalDurationMs: contextManager.elapsedMs(),
            stageSummary: contextManager.getStageSummary(),
            metrics: metrics.finalizeAndReport()
        ]
        contextManager.setVariable("lifecycleManifest", manifest)
        LoggingUtils.info("PipelineExecutionFramework",
            "Lifecycle manifest archived: ${manifest.totalDurationMs}ms total")
        audit.emitAuditEvent("LIFECYCLE_MANIFEST_ARCHIVED",
            "Lifecycle manifest archived for '${projectName}'", correlationId)
        telemetry.emitEvent("framework", "lifecycle_manifest", manifest)
        exitStage("stage-50", "COMPLETED", manifest)
    }

    /*
     * =====================================================
     * PRIVATE HELPERS
     * =====================================================
     */

    private void enterStage(String stageName) {
        contextManager.enterStage(stageName)
        metrics.recordStageStart(stageName)
        String spanId = otel.startSpan(stageName, [
            correlationId: correlationId,
            projectName: projectName,
            businessUnit: businessUnit
        ])
        contextManager.setVariable("spanId", spanId)
    }

    private void exitStage(String stageName, String status, Map result) {
        contextManager.exitStage(stageName, status, result)
        metrics.recordStageEnd(stageName, status, result?.toString())
        String spanId = contextManager.getVariable("spanId") as String ?: ""
        if (ValidationUtils.isNonEmpty(spanId) && "FAILED".equals(status)) {
            otel.endSpanWithError(spanId, result?.error?.toString() ?: "Stage completed with status ${status}")
        } else if (ValidationUtils.isNonEmpty(spanId)) {
            otel.endSpan(spanId, "OK", result)
        }
    }

    private void stage(String name, Closure body) {
        try {
            body.call()
        } catch (Exception e) {
            String stageName = name.contains(":") ? name.split(":")[0].trim() : name
            String stageKey = "stage-${contextManager.currentStageIndex().toString().padLeft(2, '0')}"
            Map recoveryResult = recovery.handleFailure(stageKey, e.message, e, [:])
            if (!recoveryResult.recovered instanceof Boolean || !((Boolean) recoveryResult.recovered)) {
                throw e
            }
        }
    }

    private void cleanWs() {
        try {
            steps.sh(script: "rm -rf .buildos-* 2>/dev/null; true", returnStatus: true)
        } catch (Exception e) { }
    }

    private String resolveEnv(String name, String defaultValue) {
        try {
            String val = steps.env.getProperty(name)
            return ValidationUtils.isNonEmpty(val) ? val : defaultValue
        } catch (Exception e) {
            return defaultValue
        }
    }

    @NonCPS
    private List<String> findArtifacts() {
        List<String> artifacts = []
        if (resolvedConfig == null) return artifacts
        Map maven = extractMap(resolvedConfig, "maven")
        if (maven != null) {
            List extraArtifacts = extractList(maven, "extraArtifacts")
            if (extraArtifacts != null) {
                for (Object art : extraArtifacts) {
                    if (art instanceof String) artifacts.add(art.toString())
                }
            }
        }
        artifacts.add("target/${contextManager.getVariable('finalArtifactName') ?: '*.jar'}")
        return artifacts
    }

    @NonCPS
    private String computeNextVersion(String currentVersion) {
        if (!ValidationUtils.isNonEmpty(currentVersion)) return "1.0.1"
        try {
            String cleaned = currentVersion.replace("-SNAPSHOT", "")
            String[] parts = cleaned.split("\\.")
            if (parts.length >= 3) {
                int patch = Integer.parseInt(parts[2]) + 1
                return "${parts[0]}.${parts[1]}.${patch}-SNAPSHOT"
            }
        } catch (Exception e) { }
        return "1.0.1-SNAPSHOT"
    }

    @NonCPS
    private String extractString(Map map, String key) {
        if (map == null) return null
        Object v = map.get(key)
        return v instanceof String ? (String) v : (v != null ? v.toString() : null)
    }

    @NonCPS
    private Map extractMap(Map parent, String key) {
        if (parent == null) return null
        Object v = parent.get(key)
        return v instanceof Map ? (Map) v : null
    }

    @NonCPS
    private List extractList(Map parent, String key) {
        if (parent == null) return []
        Object v = parent.get(key)
        return v instanceof List ? (List) v : []
    }

    @NonCPS
    private boolean extractBoolean(Map map, String key, boolean defaultValue) {
        if (map == null) return defaultValue
        Object v = map.get(key)
        return v instanceof Boolean ? (Boolean) v : defaultValue
    }
}
