package com.enterprise.platform.quality

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class QualityGateManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_POLL_INTERVAL_MS = 5000
    private static final int DEFAULT_POLL_TIMEOUT_MS = 300000
    private static final int DEFAULT_COVERAGE_THRESHOLD = 80
    private static final List<String> FAILED_GATE_STATUSES = ["ERROR", "FAILED"]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    QualityGateManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    QualityGateManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map evaluateQualityGate(Map sonarQubeResult, Map coverageData, Map thresholds) {
        LoggingUtils.info("QualityGateManager",
            "Evaluating quality gate [correlationId=${correlationId}]")

        long startTime = System.currentTimeMillis()
        List<Map<String, Object>> conditions = []
        List<Map<String, Object>> violations = []
        List<Map<String, Object>> warnings = []

        try {
            if (thresholds == null) thresholds = [:]

            /*
             * Evaluate SonarQube quality gate
             */
            if (sonarQubeResult != null) {
                Map gateResult = evaluateSonarGate(sonarQubeResult)
                conditions.addAll(gateResult.conditions ?: [])
                if (gateResult.violations instanceof List) {
                    violations.addAll((List<Map<String, Object>>) gateResult.violations)
                }
                if (gateResult.warnings instanceof List) {
                    warnings.addAll((List<Map<String, Object>>) gateResult.warnings)
                }
            } else {
                warnings.add([
                    type: "SONARQUBE_NOT_EXECUTED",
                    message: "SonarQube analysis was not executed. Quality gate evaluation is partial."
                ])
            }

            /*
             * Evaluate coverage thresholds
             */
            if (coverageData != null) {
                Map coverageResult = evaluateCoverageConditions(coverageData, thresholds)
                conditions.addAll(coverageResult.conditions ?: [])
                if (coverageResult.violations instanceof List) {
                    violations.addAll((List<Map<String, Object>>) coverageResult.violations)
                }
                if (coverageResult.warnings instanceof List) {
                    warnings.addAll((List<Map<String, Object>>) coverageResult.warnings)
                }
            }

            /*
             * Evaluate security hotspot conditions
             */
            if (sonarQubeResult != null) {
                Map securityResult = evaluateSecurityConditions(sonarQubeResult)
                conditions.addAll(securityResult.conditions ?: [])
                if (securityResult.violations instanceof List) {
                    violations.addAll((List<Map<String, Object>>) securityResult.violations)
                }
            }

            long duration = System.currentTimeMillis() - startTime
            boolean passed = violations.isEmpty()
            String gateStatus = passed ? (warnings.isEmpty() ? "PASSED" : "PASSED_WITH_WARNINGS") : "FAILED"

            Map evidenceReport = buildEvidenceReport(gateStatus, conditions, violations, warnings, duration)

            LoggingUtils.info("QualityGateManager",
                "Quality gate evaluated: ${gateStatus} (${conditions.size()} conditions, ${violations.size()} violations, ${warnings.size()} warnings) in ${duration}ms [correlationId=${correlationId}]")

            audit.emitAuditEvent("QUALITY_GATE_EVALUATED",
                "Quality gate: ${gateStatus} (${violations.size()} violations)", correlationId)
            telemetry.emitEvent("quality.gate", "evaluated", [
                correlationId: correlationId,
                status: gateStatus,
                totalConditions: conditions.size(),
                violations: violations.size(),
                warnings: warnings.size(),
                durationMs: duration
            ])

            if (!passed) {
                String msg = "Quality gate FAILED: ${violations.size()} violation(s)"
                for (Map v : violations) {
                    msg += "\n  - ${v.message}"
                }
                LoggingUtils.error("QualityGateManager", msg, null)
                audit.emitAuditEvent("QUALITY_GATE_FAILED", msg, correlationId)
                throw new RuntimeException(msg)
            }

            return [
                status: gateStatus,
                passed: passed,
                correlationId: correlationId,
                durationMs: duration,
                conditions: conditions,
                violations: violations,
                warnings: warnings,
                evidenceReport: evidenceReport
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("Quality gate FAILED")) throw e
            String errMsg = "Quality gate evaluation error: ${e.message}"
            LoggingUtils.error("QualityGateManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected quality gate evaluation error: ${e.message}"
            LoggingUtils.error("QualityGateManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map pollQualityGate(SonarQubeManager sonarManager, String projectKey,
                        String sonarHost, String sonarToken, Integer timeoutMs) {
        LoggingUtils.info("QualityGateManager",
            "Polling SonarQube quality gate for '${projectKey}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(projectKey)) {
            throw new IllegalArgumentException("Project key must not be null or empty")
        }

        int effectiveTimeout = timeoutMs != null ? timeoutMs : DEFAULT_POLL_TIMEOUT_MS
        int elapsed = 0
        int pollCount = 0
        Map lastResult = [:]

        while (elapsed < effectiveTimeout) {
            try {
                steps.sleep(time: DEFAULT_POLL_INTERVAL_MS / 1000, unit: "SECONDS")
                elapsed += DEFAULT_POLL_INTERVAL_MS
                pollCount++

                lastResult = sonarManager.retrieveQualityGateStatus(projectKey, sonarHost, sonarToken)
                String gateStatus = lastResult.status?.toString() ?: "UNKNOWN"

                if ("PASSED".equals(gateStatus) || "OK".equals(gateStatus)) {
                    LoggingUtils.info("QualityGateManager",
                        "Quality gate PASSED after ${elapsed}ms (${pollCount} polls) [correlationId=${correlationId}]")
                    return [
                        status: "PASSED",
                        projectKey: projectKey,
                        pollingDurationMs: elapsed,
                        pollCount: pollCount,
                        details: lastResult
                    ]
                }

                if (FAILED_GATE_STATUSES.contains(gateStatus)) {
                    LoggingUtils.error("QualityGateManager",
                        "Quality gate FAILED after ${elapsed}ms: status=${gateStatus}", null)
                    return [
                        status: "FAILED",
                        projectKey: projectKey,
                        pollingDurationMs: elapsed,
                        pollCount: pollCount,
                        details: lastResult
                    ]
                }

            } catch (Exception e) {
                LoggingUtils.warn("QualityGateManager",
                    "Error polling quality gate (attempt ${pollCount}): ${e.message}")
            }
        }

        String timeoutMsg = "Quality gate polling timed out after ${effectiveTimeout}ms (${pollCount} polls)"
        LoggingUtils.error("QualityGateManager", timeoutMsg, null)
        return [
            status: "TIMEOUT",
            projectKey: projectKey,
            pollingDurationMs: elapsed,
            pollCount: pollCount,
            details: lastResult,
            error: timeoutMsg
        ]
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Evaluation sub-routines
     */

    @NonCPS
    private Map evaluateSonarGate(Map sonarResult) {
        List<Map<String, Object>> conditions = []
        List<Map<String, Object>> violations = []
        List<Map<String, Object>> warnings = []
        List<Map> rawConditions = []

        Object conditionsRaw = sonarResult.get("conditions")
        if (conditionsRaw instanceof List) {
            rawConditions = (List<Map>) conditionsRaw
        } else {
            Map projectStatus = sonarResult.projectStatus instanceof Map ?
                (Map) sonarResult.projectStatus : null
            if (projectStatus != null) {
                Object cs = projectStatus.get("conditions")
                if (cs instanceof List) rawConditions = (List<Map>) cs
            }
        }

        for (Map cond : rawConditions) {
            String metric = cond.metric?.toString() ?: "unknown"
            String condStatus = cond.status?.toString() ?: "OK"
            String value = cond.value?.toString() ?: ""
            String errorThreshold = cond.errorThreshold?.toString() ?: ""
            String operator = cond.operator?.toString() ?: ""

            Map conditionEntry = [
                metric: metric,
                status: condStatus,
                value: value,
                threshold: errorThreshold,
                operator: operator
            ]
            conditions.add(conditionEntry)

            if ("ERROR".equals(condStatus)) {
                violations.add([
                    type: "QUALITY_GATE_CONDITION",
                    metric: metric,
                    actual: value,
                    threshold: errorThreshold,
                    message: "SonarQube quality gate condition '${metric}' failed: ${value} (threshold: ${errorThreshold})"
                ])
            } else if ("WARN".equals(condStatus)) {
                warnings.add([
                    type: "QUALITY_GATE_CONDITION_WARNING",
                    metric: metric,
                    actual: value,
                    threshold: errorThreshold,
                    message: "SonarQube quality gate condition '${metric}' at warning level: ${value} (threshold: ${errorThreshold})"
                ])
            }
        }

        String gateStatus = sonarResult.status?.toString() ?: "UNKNOWN"
        if ("FAILED".equals(gateStatus) || "ERROR".equals(gateStatus)) {
            if (violations.isEmpty()) {
                violations.add([
                    type: "QUALITY_GATE_FAILED",
                    status: gateStatus,
                    message: "SonarQube quality gate overall status: ${gateStatus}"
                ])
            }
        }

        return [conditions: conditions, violations: violations, warnings: warnings]
    }

    @NonCPS
    private Map evaluateCoverageConditions(Map coverageData, Map thresholds) {
        List<Map<String, Object>> conditions = []
        List<Map<String, Object>> violations = []
        List<Map<String, Object>> warnings = []

        double lineThreshold = thresholds.lineCoverage instanceof Number ?
            ((Number) thresholds.lineCoverage).doubleValue() : DEFAULT_COVERAGE_THRESHOLD
        double branchThreshold = thresholds.branchCoverage instanceof Number ?
            ((Number) thresholds.branchCoverage).doubleValue() : 70.0

        double lineActual = coverageData.lineCoverage instanceof Number ?
            ((Number) coverageData.lineCoverage).doubleValue() : 0.0
        double branchActual = coverageData.branchCoverage instanceof Number ?
            ((Number) coverageData.branchCoverage).doubleValue() : 0.0

        conditions.add([
            metric: "line_coverage",
            status: lineActual >= lineThreshold ? "OK" : "ERROR",
            value: String.format("%.1f", lineActual),
            threshold: String.format("%.1f", lineThreshold),
            operator: "GT"
        ])
        conditions.add([
            metric: "branch_coverage",
            status: branchActual >= branchThreshold ? "OK" : "ERROR",
            value: String.format("%.1f", branchActual),
            threshold: String.format("%.1f", branchThreshold),
            operator: "GT"
        ])

        if (lineActual < lineThreshold) {
            violations.add([
                type: "COVERAGE_THRESHOLD",
                metric: "line_coverage",
                actual: lineActual,
                threshold: lineThreshold,
                message: "Line coverage ${String.format('%.1f', lineActual)}% is below threshold ${String.format('%.1f', lineThreshold)}%"
            ])
        }
        if (branchActual < branchThreshold) {
            violations.add([
                type: "COVERAGE_THRESHOLD",
                metric: "branch_coverage",
                actual: branchActual,
                threshold: branchThreshold,
                message: "Branch coverage ${String.format('%.1f', branchActual)}% is below threshold ${String.format('%.1f', branchThreshold)}%"
            ])
        }

        return [conditions: conditions, violations: violations, warnings: warnings]
    }

    @NonCPS
    private Map evaluateSecurityConditions(Map sonarResult) {
        List<Map<String, Object>> conditions = []
        List<Map<String, Object>> violations = []

        int blockerIssues = 0
        int criticalIssues = 0
        int securityHotspots = 0

        if (sonarResult.issues instanceof List) {
            for (Object issueObj : (List) sonarResult.issues) {
                if (!(issueObj instanceof Map)) continue
                Map issue = (Map) issueObj
                String severity = issue.severity?.toString() ?: ""
                String type = issue.type?.toString() ?: ""
                if ("BLOCKER".equals(severity)) blockerIssues++
                if ("CRITICAL".equals(severity)) criticalIssues++
                if ("SECURITY_HOTSPOT".equals(type)) securityHotspots++
            }
        }

        conditions.add([metric: "blocker_issues", status: blockerIssues > 0 ? "ERROR" : "OK",
                        value: blockerIssues.toString(), threshold: "0", operator: "EQ"])
        conditions.add([metric: "security_hotspots", status: securityHotspots > 50 ? "WARN" : "OK",
                        value: securityHotspots.toString(), threshold: "50", operator: "LT"])

        if (blockerIssues > 0) {
            violations.add([
                type: "BLOCKER_ISSUES",
                count: blockerIssues,
                message: "${blockerIssues} blocker issue(s) found — must be resolved before proceeding"
            ])
        }

        return [conditions: conditions, violations: violations, warnings: []]
    }

    @NonCPS
    private Map buildEvidenceReport(String status, List<Map<String, Object>> conditions,
                                    List<Map<String, Object>> violations,
                                    List<Map<String, Object>> warnings, long durationMs) {
        Map report = [:]
        report["status"] = status
        report["evaluatedAt"] = formatTimestamp()
        report["correlationId"] = correlationId
        report["durationMs"] = durationMs
        report["totalConditions"] = conditions.size()
        report["conditions"] = conditions
        report["violations"] = violations
        report["warnings"] = warnings

        int passedConditions = countConditionStatus(conditions, "OK")
        int failedConditions = countConditionStatus(conditions, "ERROR")
        int warnedConditions = countConditionStatus(conditions, "WARN")

        report["passedConditions"] = passedConditions
        report["failedConditions"] = failedConditions
        report["warnedConditions"] = warnedConditions

        return report
    }

    @NonCPS
    private int countConditionStatus(List<Map<String, Object>> conditions, String status) {
        int count = 0
        for (Map c : conditions) {
            if (status.equals(c.status?.toString())) count++
        }
        return count
    }

    @NonCPS
    private String formatTimestamp() {
        try {
            return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            return ""
        }
    }
}
