package com.enterprise.platform.quality

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class SonarQubeManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String DEFAULT_SONAR_HOST = "http://sonarqube:9000"
    private static final int DEFAULT_CE_TIMEOUT_MS = 300000
    private static final int CE_POLL_INTERVAL_MS = 5000
    private static final int MAX_RETRIEVED_ISSUES = 500

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId

    SonarQubeManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    SonarQubeManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map executeAnalysis(String projectKey, Map config) {
        LoggingUtils.info("SonarQubeManager",
            "Executing SonarQube analysis for project '${projectKey}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(projectKey)) {
            throw new IllegalArgumentException("Project key must not be null or empty")
        }
        if (config == null) config = [:]

        long startTime = System.currentTimeMillis()
        String sonarHost = config.sonarHostUrl instanceof String ?
            config.sonarHostUrl.toString() : DEFAULT_SONAR_HOST
        String token = config.sonarToken instanceof String ? config.sonarToken.toString() : ""

        try {
            List<String> cmdArgs = buildScannerCommand(projectKey, sonarHost, token, config)
            String cmd = cmdArgs.join(" ")

            Map execResult = shellUtils.execute(cmd, [
                timeoutMs: resolveAnalysisTimeout(config),
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            String ceTaskId = extractCeTaskId(execResult.stdout?.toString() ?: "")
            if (!ValidationUtils.isNonEmpty(ceTaskId)) {
                ceTaskId = extractCeTaskId(execResult.stderr?.toString() ?: "")
            }

            Map analysisResult
            if (ValidationUtils.isNonEmpty(ceTaskId)) {
                analysisResult = waitForCeTask(ceTaskId, sonarHost, token, config)
            } else {
                analysisResult = [status: "COMPLETED", ceTaskId: "", taskStatus: "NO_TASK_RETURNED"]
                LoggingUtils.warn("SonarQubeManager",
                    "No CE task ID returned from scanner. Proceeding without async task tracking. [correlationId=${correlationId}]")
            }

            long duration = System.currentTimeMillis() - startTime
            analysisResult["durationMs"] = duration
            analysisResult["projectKey"] = projectKey

            LoggingUtils.info("SonarQubeManager",
                "SonarQube analysis completed in ${duration}ms [correlationId=${correlationId}]")
            audit.emitAuditEvent("SONAR_ANALYSIS_COMPLETED",
                "SonarQube analysis for '${projectKey}' completed: ${analysisResult.status}", correlationId)
            telemetry.emitEvent("quality.sonar", "analysis_completed", [
                correlationId: correlationId,
                projectKey: projectKey,
                durationMs: duration,
                status: analysisResult.status
            ])

            return analysisResult

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "SonarQube analysis failed for '${projectKey}': ${e.message}"
            LoggingUtils.error("SonarQubeManager", errMsg, e)
            audit.emitAuditEvent("SONAR_ANALYSIS_FAILED", errMsg, correlationId)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map retrieveQualityGateStatus(String projectKey, String sonarHost, String token) {
        LoggingUtils.info("SonarQubeManager",
            "Retrieving quality gate status for '${projectKey}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(projectKey)) {
            throw new IllegalArgumentException("Project key must not be null or empty")
        }

        try {
            String host = ValidationUtils.isNonEmpty(sonarHost) ? sonarHost : DEFAULT_SONAR_HOST
            String authToken = ValidationUtils.isNonEmpty(token) ? token : ""
            String qualityGateUrl = "${host}/api/qualitygates/project_status?projectKey=${urlEncode(projectKey)}"

            Map response = callSonarApi(qualityGateUrl, authToken)
            Map parsed = parseQualityGateResponse(response)

            LoggingUtils.info("SonarQubeManager",
                "Quality gate for '${projectKey}': ${parsed.status} [correlationId=${correlationId}]")
            return parsed

        } catch (Exception e) {
            String errMsg = "Failed to retrieve quality gate status: ${e.message}"
            LoggingUtils.error("SonarQubeManager", errMsg, e)
            return [
                status: "ERROR",
                error: errMsg,
                projectKey: projectKey
            ]
        }
    }

    Map retrieveIssues(String projectKey, String sonarHost, String token, Map filters) {
        LoggingUtils.info("SonarQubeManager",
            "Retrieving issues for '${projectKey}' [correlationId=${correlationId}]")

        try {
            String host = ValidationUtils.isNonEmpty(sonarHost) ? sonarHost : DEFAULT_SONAR_HOST
            String authToken = ValidationUtils.isNonEmpty(token) ? token : ""
            String severity = filters?.severity instanceof String ? filters.severity.toString() : "MAJOR"

            String issuesUrl = "${host}/api/issues/search?projectKeys=${urlEncode(projectKey)}" +
                "&severities=${urlEncode(severity)}&ps=${MAX_RETRIEVED_ISSUES}&resolved=false"

            if (filters?.types instanceof List) {
                String types = ((List) filters.types).join(",")
                issuesUrl += "&types=${urlEncode(types)}"
            }

            Map response = callSonarApi(issuesUrl, authToken)
            List<Map> issues = parseIssuesResponse(response)

            int totalIssues = response.total instanceof Number ? ((Number) response.total).intValue() : 0
            int blockerCount = 0
            int criticalCount = 0
            int majorCount = 0
            int minorCount = 0
            int infoCount = 0

            for (Map issue : issues) {
                switch (issue.severity) {
                    case "BLOCKER": blockerCount++; break
                    case "CRITICAL": criticalCount++; break
                    case "MAJOR": majorCount++; break
                    case "MINOR": minorCount++; break
                    default: infoCount++; break
                }
            }

            return [
                status: "COMPLETED",
                projectKey: projectKey,
                total: totalIssues,
                blockerCount: blockerCount,
                criticalCount: criticalCount,
                majorCount: majorCount,
                minorCount: minorCount,
                infoCount: infoCount,
                issues: issues
            ]

        } catch (Exception e) {
            String errMsg = "Failed to retrieve issues for '${projectKey}': ${e.message}"
            LoggingUtils.error("SonarQubeManager", errMsg, e)
            return [status: "ERROR", error: errMsg, projectKey: projectKey]
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private List<String> buildScannerCommand(String projectKey, String sonarHost,
                                             String token, Map config) {
        List<String> args = ["sonar-scanner"]
        args.add("-Dsonar.projectKey=${projectKey}")
        args.add("-Dsonar.host.url=${sonarHost}")
        args.add("-Dsonar.projectName=${projectKey}")

        if (ValidationUtils.isNonEmpty(token)) {
            args.add("-Dsonar.token=${token}")
        }

        if (config.projectVersion instanceof String) {
            args.add("-Dsonar.projectVersion=${config.projectVersion}")
        }
        if (config.branchName instanceof String) {
            args.add("-Dsonar.branch.name=${config.branchName}")
        }
        if (config.branchTarget instanceof String) {
            args.add("-Dsonar.branch.target=${config.branchTarget}")
        }
        if (config.sources instanceof String) {
            args.add("-Dsonar.sources=${config.sources}")
        } else {
            args.add("-Dsonar.sources=.")
        }
        if (config.javaBinaries instanceof String) {
            args.add("-Dsonar.java.binaries=${config.javaBinaries}")
        }
        if (config.inclusions instanceof String) {
            args.add("-Dsonar.inclusions=${config.inclusions}")
        }
        if (config.exclusions instanceof String) {
            args.add("-Dsonar.exclusions=${config.exclusions}")
        }
        if (config.coverageReportPaths instanceof String) {
            args.add("-Dsonar.coverage.jacoco.xmlReportPaths=${config.coverageReportPaths}")
        }
        if (config.coberturaReportPath instanceof String) {
            args.add("-Dsonar.cobertura.reportPath=${config.coberturaReportPath}")
        }

        Boolean skipTests = config.skipTests instanceof Boolean ? (Boolean) config.skipTests : true
        if (skipTests) {
            args.add("-Dsonar.skipTests=true")
        }

        if (config.additionalProperties instanceof Map) {
            Map props = (Map) config.additionalProperties
            for (Map.Entry entry : props.entrySet()) {
                args.add("-D${entry.key}=${entry.value}")
            }
        }

        return args
    }

    private Map waitForCeTask(String ceTaskId, String sonarHost, String token, Map config) {
        LoggingUtils.info("SonarQubeManager",
            "Waiting for Compute Engine task '${ceTaskId}' to complete [correlationId=${correlationId}]")

        int timeoutMs = config.ceTimeoutMs instanceof Number ?
            ((Number) config.ceTimeoutMs).intValue() : DEFAULT_CE_TIMEOUT_MS
        int elapsed = 0
        String taskStatus = "PENDING"
        String analysisId = ""
        Map taskResult = [:]

        while (elapsed < timeoutMs) {
            try {
                steps.sleep(time: CE_POLL_INTERVAL_MS / 1000, unit: "SECONDS")
                elapsed += CE_POLL_INTERVAL_MS

                String taskUrl = "${sonarHost}/api/ce/task?id=${ceTaskId}"
                Map response = callSonarApi(taskUrl, token)
                taskResult = response.task instanceof Map ? (Map) response.task : response

                taskStatus = taskResult.status?.toString() ?: taskResult.task?.status?.toString() ?: "UNKNOWN"
                analysisId = taskResult.analysisId?.toString() ?:
                    taskResult.task?.analysisId?.toString() ?: ""

                if ("SUCCESS".equals(taskStatus)) {
                    LoggingUtils.info("SonarQubeManager",
                        "CE task '${ceTaskId}' completed successfully after ${elapsed}ms [correlationId=${correlationId}]")
                    break
                }
                if ("FAILED".equals(taskStatus) || "CANCELED".equals(taskStatus)) {
                    String errorMsg = taskResult.errorMessage?.toString() ?:
                        taskResult.task?.errorMessage?.toString() ?: "Unknown CE task failure"
                    throw new RuntimeException(
                        "SonarQube CE task '${ceTaskId}' ${taskStatus}: ${errorMsg}")
                }
                if ("PENDING".equals(taskStatus) || "IN_PROGRESS".equals(taskStatus)) {
                    continue
                }

            } catch (RuntimeException e) {
                if (e.message.contains("FAILED") || e.message.contains("CANCELED")) throw e
                LoggingUtils.warn("SonarQubeManager",
                    "Error polling CE task: ${e.message}")
            } catch (Exception e) {
                LoggingUtils.warn("SonarQubeManager",
                    "Error polling CE task: ${e.message}")
            }
        }

        if (!"SUCCESS".equals(taskStatus) && elapsed >= timeoutMs) {
            throw new RuntimeException(
                "SonarQube CE task '${ceTaskId}' did not complete within ${timeoutMs}ms. Last status: ${taskStatus}")
        }

        return [
            status: "COMPLETED",
            ceTaskId: ceTaskId,
            taskStatus: taskStatus,
            analysisId: analysisId,
            pollingDurationMs: elapsed
        ]
    }

    private Map callSonarApi(String url, String token) {
        try {
            List headers = []
            if (ValidationUtils.isNonEmpty(token)) {
                String encoded = steps.sh(
                    script: "echo -n '${token}:' | base64 -w 0",
                    returnStdout: true
                ).toString().trim()
                headers.add([name: "Authorization", value: "Basic ${encoded}"])
            }

            def response = steps.httpRequest(
                url: url,
                httpMode: "GET",
                contentType: "APPLICATION_JSON",
                customHeaders: headers,
                validResponseCodes: "200:599",
                quiet: true,
                wrapAsMultipart: false
            )

            int statusCode = response.status instanceof Integer ?
                (Integer) response.status : Integer.parseInt(response.status.toString())

            if (statusCode != 200) {
                throw new RuntimeException("SonarQube API returned HTTP ${statusCode}: ${response.content}")
            }

            String content = response.content instanceof String ?
                response.content.toString() : "{}"
            try {
                return new groovy.json.JsonSlurper().parseText(content)
            } catch (Exception e) {
                return [:]
            }

        } catch (Exception e) {
            String errMsg = "SonarQube API call failed for '${url}': ${e.message}"
            LoggingUtils.error("SonarQubeManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    @NonCPS
    private String extractCeTaskId(String scannerOutput) {
        if (!ValidationUtils.isNonEmpty(scannerOutput)) return ""
        def matcher = scannerOutput =~ /ceTaskId[=:]\s*"?([a-zA-Z0-9\-]+)"?/
        if (matcher.find()) return matcher.group(1)
        matcher = scannerOutput =~ /task\?id=([a-zA-Z0-9\-]+)/
        if (matcher.find()) return matcher.group(1)
        return ""
    }

    @NonCPS
    private Map parseQualityGateResponse(Map response) {
        Map result = [status: "UNKNOWN"]
        if (response == null) return result

        Map projectStatus = response.projectStatus instanceof Map ?
            (Map) response.projectStatus : null
        if (projectStatus == null) return result

        String status = projectStatus.status?.toString() ?: "UNKNOWN"
        result["status"] = status
        result["projectStatus"] = projectStatus

        List conditions = projectStatus.conditions instanceof List ?
            (List) projectStatus.conditions : []
        List<Map> parsedConditions = []
        for (Object condObj : conditions) {
            if (!(condObj instanceof Map)) continue
            Map cond = (Map) condObj
            parsedConditions.add([
                metric: cond.metric?.toString() ?: "",
                operator: cond.operator?.toString() ?: "",
                value: cond.value?.toString() ?: "",
                status: cond.status?.toString() ?: "OK",
                errorThreshold: cond.errorThreshold?.toString() ?: "",
                warningThreshold: cond.warningThreshold?.toString() ?: ""
            ])
        }
        result["conditions"] = parsedConditions

        return result
    }

    @NonCPS
    private List<Map> parseIssuesResponse(Map response) {
        List<Map> issues = []
        if (response == null) return issues

        List rawIssues = response.issues instanceof List ? (List) response.issues : []
        for (Object issueObj : rawIssues) {
            if (!(issueObj instanceof Map)) continue
            Map issue = (Map) issueObj
            issues.add([
                key: issue.key?.toString() ?: "",
                rule: issue.rule?.toString() ?: "",
                severity: issue.severity?.toString() ?: "MAJOR",
                component: issue.component?.toString() ?: "",
                project: issue.project?.toString() ?: "",
                line: issue.line instanceof Number ? ((Number) issue.line).intValue() : 0,
                message: issue.message?.toString() ?: "",
                effort: issue.effort?.toString() ?: "",
                debt: issue.debt?.toString() ?: "",
                type: issue.type?.toString() ?: "CODE_SMELL",
                status: issue.status?.toString() ?: "OPEN",
                resolution: issue.resolution?.toString() ?: "",
                assignee: issue.assignee?.toString() ?: ""
            ])
        }
        return issues
    }

    @NonCPS
    private int resolveAnalysisTimeout(Map config) {
        if (config.timeoutMs instanceof Number) {
            return ((Number) config.timeoutMs).intValue()
        }
        return 600000
    }

    @NonCPS
    private String urlEncode(String value) {
        if (value == null) return ""
        try {
            return java.net.URLEncoder.encode(value, "UTF-8")
                .replace("+", "%20")
        } catch (Exception e) {
            return value
        }
    }
}
