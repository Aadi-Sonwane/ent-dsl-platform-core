package com.enterprise.platform.maven

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class BuildLifecycleManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> STANDARD_PHASES = [
        "clean", "validate", "compile", "test-compile", "test",
        "package", "verify", "install", "deploy"
    ]

    private static final List<String> COMPILATION_PHASES = ["clean", "compile", "test-compile"]
    private static final List<String> TESTING_PHASES = ["test"]
    private static final List<String> PACKAGING_PHASES = ["package", "verify"]
    private static final List<String> PUBLISHING_PHASES = ["install", "deploy"]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final DynamicMavenSettingsGenerator settingsGenerator
    private final ArtifactNamingStrategy namingStrategy
    private final String correlationId

    private String nexusUrl
    private Map projectConfig
    private String generatedSettingsXml
    private String generatedDistMgmtXml

    BuildLifecycleManager(Object steps) {
        this.steps = steps
        this.correlationId = java.util.UUID.randomUUID().toString()
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.settingsGenerator = new DynamicMavenSettingsGenerator(steps, this.correlationId)
        this.namingStrategy = new ArtifactNamingStrategy(steps, this.correlationId)
    }

    BuildLifecycleManager(Object steps, String correlationId) {
        this.steps = steps
        this.correlationId = correlationId
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.settingsGenerator = new DynamicMavenSettingsGenerator(steps, this.correlationId)
        this.namingStrategy = new ArtifactNamingStrategy(steps, this.correlationId)
    }

    BuildLifecycleManager initialize(String nexusUrl, Map projectConfig) {
        LoggingUtils.info("BuildLifecycleManager",
            "Initializing with nexusUrl and projectConfig [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(nexusUrl)) {
            throw new IllegalArgumentException("Nexus URL must not be null or empty")
        }
        if (projectConfig == null) {
            throw new IllegalArgumentException("Project configuration must not be null")
        }

        this.nexusUrl = nexusUrl
        this.projectConfig = deepCloneConfig(projectConfig)

        try {
            this.generatedSettingsXml = settingsGenerator.generateSettingsXml(nexusUrl, projectConfig)
            LoggingUtils.info("BuildLifecycleManager",
                "Settings XML generated (${this.generatedSettingsXml.length()} chars) [correlationId=${correlationId}]")
        } catch (Exception e) {
            String errMsg = "Failed to generate settings.xml during initialization: ${e.message}"
            LoggingUtils.error("BuildLifecycleManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }

        return this
    }

    Map executeBuildPhase(String phase) {
        LoggingUtils.info("BuildLifecycleManager",
            "Executing Maven build phase '${phase}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(phase)) {
            throw new IllegalArgumentException("Build phase must not be null or empty")
        }
        if (this.generatedSettingsXml == null) {
            throw new IllegalStateException("BuildLifecycleManager not initialized. Call initialize() first.")
        }

        String normalizedPhase = phase.trim().toLowerCase()
        if (!STANDARD_PHASES.contains(normalizedPhase)) {
            LoggingUtils.warn("BuildLifecycleManager",
                "Phase '${normalizedPhase}' is not a standard Maven phase. Executing as custom goal. [correlationId=${correlationId}]")
        }

        long phaseStartTime = System.currentTimeMillis()

        try {
            writeSettingsFile()

            String mavenHome = resolveMavenHome()
            String pomPath = resolvePomPath()
            List<String> goals = buildGoalsForPhase(normalizedPhase)
            List<String> additionalArgs = buildAdditionalArguments(normalizedPhase)
            Map executionResult

            /*
             * Execute Maven via ShellUtils wrapper
             */
            try {
                List<String> cmdArgs = []
                cmdArgs.add("${mavenHome}/bin/mvn")
                cmdArgs.add("-f")
                cmdArgs.add(pomPath)
                cmdArgs.add("-s")
                cmdArgs.add(getSettingsFilePath())
                cmdArgs.addAll(goals)
                cmdArgs.addAll(additionalArgs)

                if (normalizedPhase == "deploy" && this.generatedDistMgmtXml != null) {
                    writeDistMgmtFile()
                    cmdArgs.add("-DdistributionManagementFile=${getDistMgmtFilePath()}")
                }

                String mvnCommand = cmdArgs.join(" ")

                executionResult = shellUtils.execute(
                    mvnCommand,
                    [
                        timeoutMs: resolveBuildTimeout(normalizedPhase),
                        captureOutput: true,
                        validExitCodes: [0]
                    ]
                )

            } catch (Exception e) {
                String errMsg = "Maven build phase '${normalizedPhase}' execution failed: ${e.message}"
                LoggingUtils.error("BuildLifecycleManager", errMsg, e)
                audit.emitAuditEvent("BUILD_PHASE_FAILED",
                    "Maven phase '${normalizedPhase}' failed: ${e.message}", correlationId)
                telemetry.emitEvent("maven.build", "phase_failed", [
                    correlationId: correlationId,
                    phase: normalizedPhase,
                    error: e.message
                ])
                throw new RuntimeException(errMsg, e)
            }

            long phaseDuration = System.currentTimeMillis() - phaseStartTime
            Map result = buildPhaseResult(normalizedPhase, executionResult, phaseDuration)

            LoggingUtils.info("BuildLifecycleManager",
                "Maven phase '${normalizedPhase}' completed in ${phaseDuration}ms [correlationId=${correlationId}]")
            audit.emitAuditEvent("BUILD_PHASE_COMPLETED",
                "Maven phase '${normalizedPhase}' completed in ${phaseDuration}ms", correlationId)
            telemetry.emitEvent("maven.build", "phase_completed", [
                correlationId: correlationId,
                phase: normalizedPhase,
                durationMs: phaseDuration
            ])

            return result

        } catch (RuntimeException e) {
            throw e
        } catch (Exception e) {
            String errMsg = "Unexpected error during Maven phase '${normalizedPhase}': ${e.message}"
            LoggingUtils.error("BuildLifecycleManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map executeFullBuild() {
        LoggingUtils.info("BuildLifecycleManager",
            "Executing full Maven build lifecycle [correlationId=${correlationId}]")

        long buildStartTime = System.currentTimeMillis()
        List<Map> phaseResults = []
        String buildStatus = "SUCCESS"
        String buildError = null

        try {
            for (String phase : STANDARD_PHASES) {
                LoggingUtils.info("BuildLifecycleManager",
                    "Running phase '${phase}' (${STANDARD_PHASES.indexOf(phase) + 1}/${STANDARD_PHASES.size()}) [correlationId=${correlationId}]")

                if (shouldSkipPhase(phase)) {
                    LoggingUtils.info("BuildLifecycleManager",
                        "Skipping phase '${phase}' per configuration [correlationId=${correlationId}]")
                    phaseResults.add([
                        phase: phase,
                        status: "SKIPPED",
                        durationMs: 0
                    ])
                    continue
                }

                Map phaseResult = executeBuildPhase(phase)
                phaseResults.add(phaseResult)

                if (!"SUCCESS".equals(phaseResult.status)) {
                    buildStatus = "FAILED"
                    buildError = "Phase '${phase}' failed"
                    LoggingUtils.error("BuildLifecycleManager",
                        "Build failed at phase '${phase}': ${phaseResult.error}", null)
                    break
                }
            }

            long buildDuration = System.currentTimeMillis() - buildStartTime

            Map fullResult = [
                status: buildStatus,
                correlationId: correlationId,
                durationMs: buildDuration,
                totalPhases: phaseResults.size(),
                phases: phaseResults,
                settingsGenerated: this.generatedSettingsXml != null,
                artifactName: resolveFinalArtifactName()
            ]
            if (buildError != null) {
                fullResult["error"] = buildError
            }

            LoggingUtils.info("BuildLifecycleManager",
                "Full build completed in ${buildDuration}ms: ${buildStatus} [correlationId=${correlationId}]")
            audit.emitAuditEvent("FULL_BUILD_COMPLETED",
                "Maven full build completed: ${buildStatus} in ${buildDuration}ms", correlationId)
            telemetry.emitEvent("maven.build", "full_build_completed", [
                correlationId: correlationId,
                status: buildStatus,
                durationMs: buildDuration,
                completedPhases: phaseResults.size()
            ])

            return fullResult

        } catch (Exception e) {
            long buildDuration = System.currentTimeMillis() - buildStartTime
            String errMsg = "Full build failed: ${e.message}"
            LoggingUtils.error("BuildLifecycleManager", errMsg, e)
            audit.emitAuditEvent("FULL_BUILD_FAILED", errMsg, correlationId)
            telemetry.emitEvent("maven.build", "full_build_failed", [
                correlationId: correlationId,
                durationMs: buildDuration,
                error: e.message
            ])
            return [
                status: "FAILED",
                correlationId: correlationId,
                durationMs: buildDuration,
                error: e.message,
                phases: phaseResults
            ]
        } finally {
            cleanupSettingsFiles()
        }
    }

    String getGeneratedSettingsXml() {
        return this.generatedSettingsXml
    }

    String getGeneratedDistMgmtXml() {
        return this.generatedDistMgmtXml
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Distribution management
     */

    void attachDistributionManagement(String distMgmtXml) {
        if (ValidationUtils.isNonEmpty(distMgmtXml)) {
            this.generatedDistMgmtXml = distMgmtXml
            LoggingUtils.info("BuildLifecycleManager",
                "Distribution management XML attached (${distMgmtXml.length()} chars) [correlationId=${correlationId}]")
        }
    }

    /*
     * Private helpers
     */

    private void writeSettingsFile() {
        if (this.generatedSettingsXml == null) return
        String settingsPath = getSettingsFilePath()
        try {
            steps.writeFile(file: settingsPath, text: this.generatedSettingsXml)
            LoggingUtils.info("BuildLifecycleManager",
                "Settings XML written to '${settingsPath}' [correlationId=${correlationId}]")
        } catch (Exception e) {
            String errMsg = "Failed to write settings.xml to '${settingsPath}': ${e.message}"
            LoggingUtils.error("BuildLifecycleManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    private void writeDistMgmtFile() {
        if (this.generatedDistMgmtXml == null) return
        String distPath = getDistMgmtFilePath()
        try {
            steps.writeFile(file: distPath, text: this.generatedDistMgmtXml)
            LoggingUtils.info("BuildLifecycleManager",
                "Distribution management XML written to '${distPath}' [correlationId=${correlationId}]")
        } catch (Exception e) {
            LoggingUtils.warn("BuildLifecycleManager",
                "Failed to write distribution management file: ${e.message}")
        }
    }

    private void cleanupSettingsFiles() {
        try {
            String settingsPath = getSettingsFilePath()
            steps.sh(script: "rm -f '${settingsPath}'", returnStatus: true)
        } catch (Exception e) {
            LoggingUtils.warn("BuildLifecycleManager",
                "Failed to clean up settings files: ${e.message}")
        }
    }

    @NonCPS
    private List<String> buildGoalsForPhase(String phase) {
        List<String> goals = []
        if (COMPILATION_PHASES.contains(phase)) {
            goals.add("compiler:compile")
            if ("test-compile".equals(phase)) {
                goals.add("compiler:testCompile")
            }
        }
        if (phase.equals("test")) {
            goals.add("surefire:test")
        }
        if (phase.equals("package")) {
            goals.add("jar:jar")
            goals.add("-Djar.finalName=${resolveFinalArtifactName()}")
        }
        if (phase.equals("verify")) {
            goals.add("jar:jar")
        }
        if (phase.equals("install")) {
            goals.add("install:install")
        }
        if (phase.equals("deploy")) {
            goals.add("deploy:deploy")
        }
        if (goals.isEmpty()) {
            goals.add("${phase}:${phase}")
        }
        return goals
    }

    @NonCPS
    private List<String> buildAdditionalArguments(String phase) {
        List<String> args = []
        args.add("-B")
        args.add("-V")
        args.add("-q")
        args.add("-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins=warn")

        Boolean skipTests = resolveSkipTests(phase)
        if (skipTests != null && skipTests) {
            args.add("-DskipTests=true")
            args.add("-Dmaven.test.skip=true")
        }

        String settingsPath = getSettingsFilePath()
        if (ValidationUtils.isNonEmpty(settingsPath)) {
            args.add("-s")
            args.add(settingsPath)
        }

        return args
    }

    @NonCPS
    private Boolean resolveSkipTests(String phase) {
        if (!"test".equals(phase) && !STANDARD_PHASES.indexOf("test") < STANDARD_PHASES.indexOf(phase)) {
            return false
        }
        if (this.projectConfig == null) return false
        Map mavenCfg = extractMapField(this.projectConfig, "maven")
        if (mavenCfg != null) {
            Object skipRaw = mavenCfg.get("skipTests")
            if (skipRaw instanceof Boolean) return (Boolean) skipRaw
        }
        Map project = extractMapField(this.projectConfig, "project")
        if (project != null) {
            Map build = extractMapField(project, "build")
            if (build != null) {
                Object skipRaw = build.get("skipTests")
                if (skipRaw instanceof Boolean) return (Boolean) skipRaw
            }
        }
        return false
    }

    @NonCPS
    private Boolean shouldSkipPhase(String phase) {
        if (this.projectConfig == null) return false
        Map mavenCfg = extractMapField(this.projectConfig, "maven")
        if (mavenCfg != null) {
            Object skipRaw = mavenCfg.get("skipPhases")
            if (skipRaw instanceof List) {
                List skipList = (List) skipRaw
                for (Object item : skipList) {
                    if (item instanceof String && item.toString().equalsIgnoreCase(phase)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    @NonCPS
    private int resolveBuildTimeout(String phase) {
        if (COMPILATION_PHASES.contains(phase)) return 600000
        if (TESTING_PHASES.contains(phase)) return 900000
        if (PACKAGING_PHASES.contains(phase)) return 600000
        if (PUBLISHING_PHASES.contains(phase)) return 300000
        return 600000
    }

    @NonCPS
    private String resolveMavenHome() {
        if (this.projectConfig != null) {
            Map mavenCfg = extractMapField(this.projectConfig, "maven")
            if (mavenCfg != null) {
                String home = extractStringField(mavenCfg, "mavenHome")
                if (ValidationUtils.isNonEmpty(home)) return home
                String version = extractStringField(mavenCfg, "mavenVersion")
                if (ValidationUtils.isNonEmpty(version)) {
                    return "/opt/apache-maven-${version}"
                }
            }
        }
        return "/usr/share/apache-maven"
    }

    @NonCPS
    private String resolvePomPath() {
        if (this.projectConfig != null) {
            Map mavenCfg = extractMapField(this.projectConfig, "maven")
            if (mavenCfg != null) {
                String pom = extractStringField(mavenCfg, "pomPath")
                if (ValidationUtils.isNonEmpty(pom)) return pom
            }
        }
        return "pom.xml"
    }

    @NonCPS
    private String resolveFinalArtifactName() {
        try {
            return namingStrategy.buildFinalArtifactName(this.projectConfig)
        } catch (Exception e) {
            return "buildos-application-1.0.0.jar"
        }
    }

    @NonCPS
    private Map buildPhaseResult(String phase, Map executionResult, long durationMs) {
        Map result = [phase: phase, durationMs: durationMs]
        if (executionResult != null) {
            Object exitCode = executionResult.get("exitCode")
            if (exitCode instanceof Number && ((Number) exitCode).intValue() == 0) {
                result["status"] = "SUCCESS"
            } else {
                result["status"] = "FAILED"
                result["exitCode"] = exitCode
                result["error"] = executionResult.get("stderr")
            }
            result["outputLength"] = executionResult.containsKey("stdout") ?
                executionResult.get("stdout").toString().length() : 0
        } else {
            result["status"] = "SUCCESS"
        }
        return result
    }

    @NonCPS
    private String getSettingsFilePath() {
        return "${steps.env.WORKSPACE ?: '.'}/.buildos-settings-${this.correlationId}.xml"
    }

    @NonCPS
    private String getDistMgmtFilePath() {
        return "${steps.env.WORKSPACE ?: '.'}/.buildos-distmgmt-${this.correlationId}.xml"
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

    @NonCPS
    private Map deepCloneConfig(Map source) {
        if (source == null) return [:]
        Map result = [:]
        for (Map.Entry entry : source.entrySet()) {
            String key = entry.key.toString()
            Object value = entry.value
            if (value instanceof Map) {
                result[key] = deepCloneConfig((Map) value)
            } else if (value instanceof List) {
                result[key] = deepCloneList((List) value)
            } else {
                result[key] = value
            }
        }
        return result
    }

    @NonCPS
    private List deepCloneList(List source) {
        if (source == null) return []
        List result = []
        for (Object item : source) {
            if (item instanceof Map) {
                result.add(deepCloneConfig((Map) item))
            } else if (item instanceof List) {
                result.add(deepCloneList((List) item))
            } else {
                result.add(item)
            }
        }
        return result
    }
}
