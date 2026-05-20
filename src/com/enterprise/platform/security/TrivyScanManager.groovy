package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class TrivyScanManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]
    private static final String DEFAULT_SEVERITY_THRESHOLD = "HIGH"
    private static final int MAX_REPORTED_VULNERABILITIES = 500

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId

    private String trivyBinaryPath = "trivy"
    private String severityThreshold = DEFAULT_SEVERITY_THRESHOLD
    private boolean failOnCritical = true
    private boolean failOnHigh = true
    private boolean failOnMedium = false

    TrivyScanManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    TrivyScanManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map scanFilesystem(String targetPath, Map options) {
        LoggingUtils.info("TrivyScanManager",
            "Starting Trivy filesystem scan on '${targetPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(targetPath)) {
            throw new IllegalArgumentException("Target path must not be null or empty")
        }
        if (options == null) options = [:]

        long startTime = System.currentTimeMillis()
        String outputFile = ".trivy-output-${correlationId}.json"
        List<Map> vulnerabilities = []
        List<Map> misconfigurations = []
        List<Map> secrets = []
        int totalVulnerabilities = 0
        int criticalCount = 0
        int highCount = 0
        int mediumCount = 0
        int lowCount = 0

        try {
            configureFromOptions(options)

            List<String> cmdArgs = buildScanCommand(targetPath, outputFile, options)
            String scanCommand = cmdArgs.join(" ")

            Map execResult = shellUtils.execute(scanCommand, [
                timeoutMs: resolveTimeout(options),
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            int exitCode = execResult.exitCode instanceof Number ?
                ((Number) execResult.exitCode).intValue() : -1

            /*
             * Read and parse results
             */
            String jsonOutput = readOutputFile(outputFile)
            if (ValidationUtils.isNonEmpty(jsonOutput)) {
                Map parsed = parseTrivyJson(jsonOutput)
                if (parsed != null) {
                    vulnerabilities = parsed.vulnerabilities ?: []
                    misconfigurations = parsed.misconfigurations ?: []
                    secrets = parsed.secrets ?: []
                    totalVulnerabilities = parsed.totalVulnerabilities ?: 0
                    criticalCount = parsed.criticalCount ?: 0
                    highCount = parsed.highCount ?: 0
                    mediumCount = parsed.mediumCount ?: 0
                    lowCount = parsed.lowCount ?: 0
                }
            }

            long duration = System.currentTimeMillis() - startTime
            String severityLabel = "${criticalCount} critical, ${highCount} high, ${mediumCount} medium, ${lowCount} low"

            LoggingUtils.info("TrivyScanManager",
                "Trivy scan completed in ${duration}ms: ${totalVulnerabilities} total vulnerabilities (${severityLabel}) [correlationId=${correlationId}]")

            audit.emitAuditEvent("TRIVY_SCAN_COMPLETED",
                "Filesystem scan completed: ${severityLabel}", correlationId)
            telemetry.emitEvent("security.trivy", "scan_completed", [
                correlationId: correlationId,
                targetPath: targetPath,
                durationMs: duration,
                totalVulnerabilities: totalVulnerabilities,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                lowCount: lowCount,
                exitCode: exitCode
            ])

            /*
             * Check threshold and fail if exceeded
             */
            checkThresholdViolations(criticalCount, highCount, mediumCount)

            return [
                status: exitCode == 0 || exitCode == 1 ? "COMPLETED" : "FAILED",
                correlationId: correlationId,
                targetPath: targetPath,
                durationMs: duration,
                exitCode: exitCode,
                totalVulnerabilities: totalVulnerabilities,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                lowCount: lowCount,
                severitySummary: severityLabel,
                vulnerabilities: vulnerabilities,
                misconfigurations: misconfigurations,
                secrets: secrets
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("threshold") || e.message.contains("exceeded")) {
                throw e
            }
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "Trivy scan failed: ${e.message}"
            LoggingUtils.error("TrivyScanManager", errMsg, e)
            audit.emitAuditEvent("TRIVY_SCAN_FAILED", errMsg, correlationId)
            telemetry.emitEvent("security.trivy", "scan_failed", [
                correlationId: correlationId,
                error: e.message,
                durationMs: duration
            ])
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "Unexpected Trivy scan error: ${e.message}"
            LoggingUtils.error("TrivyScanManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } finally {
            cleanupOutputFile(outputFile)
        }
    }

    Map scanImage(String imageRef, Map options) {
        LoggingUtils.info("TrivyScanManager",
            "Starting Trivy container image scan on '${imageRef}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(imageRef)) {
            throw new IllegalArgumentException("Image reference must not be null or empty")
        }
        if (options == null) options = [:]

        String outputFile = ".trivy-image-output-${correlationId}.json"

        try {
            configureFromOptions(options)

            List<String> cmdArgs = [trivyBinaryPath, "image", "--format", "json", "--output", outputFile]
            cmdArgs.add("--severity")
            cmdArgs.add(resolveSeverityFlags())
            cmdArgs.add("--scanners")
            cmdArgs.add("vuln,misconfig,secret")
            cmdArgs.add(imageRef)

            if (options.ignoreUnfixed instanceof Boolean && options.ignoreUnfixed) {
                cmdArgs.add("--ignore-unfixed")
            }
            if (options.ignorePolicy instanceof String && ValidationUtils.isNonEmpty(options.ignorePolicy.toString())) {
                cmdArgs.add("--ignore-policy")
                cmdArgs.add(options.ignorePolicy.toString())
            }

            String scanCommand = cmdArgs.join(" ")

            Map execResult = shellUtils.execute(scanCommand, [
                timeoutMs: resolveTimeout(options),
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            String jsonOutput = readOutputFile(outputFile)
            Map parsed = parseTrivyJson(jsonOutput)
            int criticalCount = parsed?.criticalCount ?: 0
            int highCount = parsed?.highCount ?: 0
            int mediumCount = parsed?.mediumCount ?: 0

            checkThresholdViolations(criticalCount, highCount, mediumCount)

            return [
                status: "COMPLETED",
                imageRef: imageRef,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                vulnerabilities: parsed?.vulnerabilities ?: []
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("threshold")) throw e
            throw e
        } catch (Exception e) {
            String errMsg = "Container image scan failed: ${e.message}"
            throw new RuntimeException(errMsg, e)
        } finally {
            cleanupOutputFile(outputFile)
        }
    }

    void setBinaryPath(String path) {
        if (ValidationUtils.isNonEmpty(path)) {
            this.trivyBinaryPath = path
        }
    }

    void setSeverityThreshold(String threshold) {
        if (ValidationUtils.isNonEmpty(threshold) && SEVERITY_ORDER.contains(threshold.toUpperCase())) {
            this.severityThreshold = threshold.toUpperCase()
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private List<String> buildScanCommand(String targetPath, String outputFile, Map options) {
        List<String> args = [trivyBinaryPath, "filesystem", "--format", "json", "--output", outputFile]
        args.add("--severity")
        args.add(resolveSeverityFlags())
        args.add("--scanners")
        args.add("vuln,misconfig,secret")

        if (options.ignoreUnfixed instanceof Boolean && options.ignoreUnfixed) {
            args.add("--ignore-unfixed")
        }
        if (options.includeSeverities instanceof List) {
            List<String> severities = []
            for (Object s : (List) options.includeSeverities) {
                if (s instanceof String) severities.add(s.toString().toUpperCase())
            }
            if (!severities.isEmpty()) {
                args.remove(args.size() - 1)
                args.add(severities.join(","))
            }
        }
        if (options.ignorePolicy instanceof String) {
            String policy = options.ignorePolicy.toString()
            if (ValidationUtils.isNonEmpty(policy)) {
                args.add("--ignore-policy")
                args.add(policy)
            }
        }
        if (options.cacheDir instanceof String && ValidationUtils.isNonEmpty(options.cacheDir.toString())) {
            args.add("--cache-dir")
            args.add(options.cacheDir.toString())
        }
        args.add("--no-progress")
        args.add(targetPath)
        return args
    }

    @NonCPS
    private String resolveSeverityFlags() {
        return "CRITICAL,HIGH,MEDIUM,LOW"
    }

    private void configureFromOptions(Map options) {
        if (options.trivyBinaryPath instanceof String) {
            String customPath = options.trivyBinaryPath.toString()
            if (ValidationUtils.isNonEmpty(customPath)) {
                this.trivyBinaryPath = customPath
            }
        }
    }

    private String readOutputFile(String outputFile) {
        try {
            if (steps.fileExists(outputFile)) {
                return steps.readFile(file: outputFile, encoding: "UTF-8")
            }
            LoggingUtils.warn("TrivyScanManager",
                "Output file '${outputFile}' not found [correlationId=${correlationId}]")
            return null
        } catch (Exception e) {
            LoggingUtils.warn("TrivyScanManager",
                "Failed to read output file '${outputFile}': ${e.message}")
            return null
        }
    }

    private void cleanupOutputFile(String outputFile) {
        try {
            if (steps.fileExists(outputFile)) {
                steps.sh(script: "rm -f '${outputFile}'", returnStatus: true)
            }
        } catch (Exception e) {
            LoggingUtils.warn("TrivyScanManager",
                "Failed to clean up output file '${outputFile}': ${e.message}")
        }
    }

    @NonCPS
    private Map parseTrivyJson(String json) {
        if (!ValidationUtils.isNonEmpty(json)) return null

        Map result = [
            vulnerabilities: [],
            misconfigurations: [],
            secrets: [],
            totalVulnerabilities: 0,
            criticalCount: 0,
            highCount: 0,
            mediumCount: 0,
            lowCount: 0
        ]

        try {
            def parsed
            try {
                parsed = new groovy.json.JsonSlurper().parseText(json)
            } catch (Exception e) {
                LoggingUtils.warn("TrivyScanManager",
                    "Failed to parse Trivy JSON output: ${e.message}")
                return result
            }

            if (parsed instanceof List) {
                for (Object resultEntry : (List) parsed) {
                    if (!(resultEntry instanceof Map)) continue
                    List targets = resultEntry.Results ?: []
                    for (Object targetObj : targets) {
                        if (!(targetObj instanceof Map)) continue
                        Map target = (Map) targetObj

                        List vulns = target.Vulnerabilities ?: []
                        for (Object v : vulns) {
                            if (!(v instanceof Map)) continue
                            Map vuln = (Map) v
                            String severity = vuln.Severity ?: "UNKNOWN"
                            if (vuln.PkgName != null && vuln.VulnerabilityID != null) {
                                Map entry = [:]
                                entry["id"] = vuln.VulnerabilityID.toString()
                                entry["package"] = vuln.PkgName.toString()
                                entry["installedVersion"] = vuln.InstalledVersion?.toString() ?: ""
                                entry["fixedVersion"] = vuln.FixedVersion?.toString() ?: "N/A"
                                entry["severity"] = severity
                                entry["title"] = vuln.Title?.toString() ?: ""
                                entry["description"] = vuln.Description?.toString() ?: ""
                                entry["publishedDate"] = vuln.PublishedDate?.toString() ?: ""
                                entry["score"] = vuln.Score instanceof Number ? ((Number) vuln.Score).doubleValue() : 0.0
                                result.vulnerabilities.add(entry)
                            }
                        }

                        List misconfigs = target.Misconfigurations ?: []
                        for (Object m : misconfigs) {
                            if (!(m instanceof Map)) continue
                            Map mis = (Map) m
                            result.misconfigurations.add([
                                id: mis.ID?.toString() ?: "",
                                type: mis.Type?.toString() ?: "",
                                title: mis.Title?.toString() ?: "",
                                severity: mis.Severity?.toString() ?: "UNKNOWN",
                                message: mis.Message?.toString() ?: "",
                                resolution: mis.Resolution?.toString() ?: ""
                            ])
                        }

                        List secs = target.Secrets ?: []
                        for (Object s : secs) {
                            if (!(s instanceof Map)) continue
                            Map sec = (Map) s
                            result.secrets.add([
                                ruleId: sec.RuleID?.toString() ?: "",
                                category: sec.Category?.toString() ?: "",
                                severity: sec.Severity?.toString() ?: "UNKNOWN",
                                match: sec.Match?.toString() ?: "",
                                file: sec.File?.toString() ?: ""
                            ])
                        }
                    }
                }
            }

            int truncatedVulns = result.vulnerabilities.size()
            if (truncatedVulns > MAX_REPORTED_VULNERABILITIES) {
                List<Map> sortedVulns = result.vulnerabilities.sort { a, b ->
                    int idxA = SEVERITY_ORDER.indexOf(a.severity)
                    int idxB = SEVERITY_ORDER.indexOf(b.severity)
                    idxA <= idxB ? -1 : 1
                }
                result.vulnerabilities = sortedVulns.take(MAX_REPORTED_VULNERABILITIES)
            }

            result.totalVulnerabilities = truncatedVulns
            result.criticalCount = countBySeverity(result.vulnerabilities, "CRITICAL")
            result.highCount = countBySeverity(result.vulnerabilities, "HIGH")
            result.mediumCount = countBySeverity(result.vulnerabilities, "MEDIUM")
            result.lowCount = countBySeverity(result.vulnerabilities, "LOW")

            return result

        } catch (Exception e) {
            LoggingUtils.warn("TrivyScanManager",
                "Error processing Trivy scan results: ${e.message}")
            return result
        }
    }

    @NonCPS
    private int countBySeverity(List<Map> vulns, String severity) {
        int count = 0
        for (Map v : vulns) {
            if (severity.equals(v.severity)) count++
        }
        return count
    }

    @NonCPS
    private void checkThresholdViolations(int criticalCount, int highCount, int mediumCount) {
        List<String> violations = []

        if (failOnCritical && criticalCount > 0) {
            violations.add("${criticalCount} critical vulnerabilit(ies) exceed the fail-on-critical threshold")
        }
        if (failOnHigh && highCount > 0) {
            violations.add("${highCount} high vulnerabilit(ies) exceed the fail-on-high threshold")
        }
        if (failOnMedium && mediumCount > 0) {
            violations.add("${mediumCount} medium vulnerabilit(ies) exceed the fail-on-medium threshold")
        }

        if (!violations.isEmpty()) {
            String violationMsg = violations.join("; ")
            LoggingUtils.error("TrivyScanManager",
                "Security scan threshold exceeded: ${violationMsg}", null)
            audit.emitAuditEvent("TRIVY_THRESHOLD_EXCEEDED",
                "Security threshold exceeded: ${violationMsg}", correlationId)
            telemetry.emitEvent("security.trivy", "threshold_exceeded", [
                correlationId: correlationId,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                summary: violationMsg
            ])
            throw new RuntimeException(
                "Trivy security scan threshold exceeded: ${violationMsg}")
        }
    }

    @NonCPS
    private int resolveTimeout(Map options) {
        if (options.timeoutMs instanceof Number) {
            return ((Number) options.timeoutMs).intValue()
        }
        return 600000
    }
}
