package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ShellUtils
import com.enterprise.platform.utils.ValidationUtils

class DependencyScanManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> SEVERITY_ORDER = ["CRITICAL", "HIGH", "MEDIUM", "LOW"]
    private static final String DEFAULT_REPORT_FORMAT = "JSON"
    private static final int MAX_REPORTED_CVES = 1000

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final ShellUtils shellUtils
    private final String correlationId

    private boolean failOnCritical = true
    private boolean failOnHigh = true

    DependencyScanManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    DependencyScanManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.shellUtils = new ShellUtils(steps)
        this.correlationId = correlationId
    }

    Map scanDependencies(String scanTarget, Map options) {
        LoggingUtils.info("DependencyScanManager",
            "Starting dependency scan on '${scanTarget}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(scanTarget)) {
            throw new IllegalArgumentException("Scan target must not be null or empty")
        }
        if (options == null) options = [:]

        long startTime = System.currentTimeMillis()
        String reportFile = ".depscan-report-${correlationId}.json"
        List<Map> vulnerabilities = []
        List<Map> dependencies = []
        int totalCves = 0
        int criticalCount = 0
        int highCount = 0
        int mediumCount = 0
        int lowCount = 0

        try {
            configureFromOptions(options)

            List<String> cmdArgs = buildScanCommand(scanTarget, reportFile, options)
            String scanCommand = cmdArgs.join(" ")

            Map execResult = shellUtils.execute(scanCommand, [
                timeoutMs: resolveTimeout(options),
                captureOutput: true,
                validExitCodes: [0, 1]
            ])

            int exitCode = execResult.exitCode instanceof Number ?
                ((Number) execResult.exitCode).intValue() : -1

            String reportContent = readReportFile(reportFile)
            if (ValidationUtils.isNonEmpty(reportContent)) {
                Map parsed = parseReport(reportContent)
                if (parsed != null) {
                    vulnerabilities = parsed.vulnerabilities ?: []
                    dependencies = parsed.dependencies ?: []
                    totalCves = parsed.totalCves ?: 0
                    criticalCount = parsed.criticalCount ?: 0
                    highCount = parsed.highCount ?: 0
                    mediumCount = parsed.mediumCount ?: 0
                    lowCount = parsed.lowCount ?: 0
                }
            }

            long duration = System.currentTimeMillis() - startTime
            String severityLabel = "${criticalCount} critical, ${highCount} high, ${mediumCount} medium, ${lowCount} low"

            LoggingUtils.info("DependencyScanManager",
                "Dependency scan completed in ${duration}ms: ${totalCves} CVEs (${severityLabel}) [correlationId=${correlationId}]")

            audit.emitAuditEvent("DEPENDENCY_SCAN_COMPLETED",
                "Dependency scan completed: ${severityLabel}", correlationId)
            telemetry.emitEvent("security.dependency", "scan_completed", [
                correlationId: correlationId,
                scanTarget: scanTarget,
                durationMs: duration,
                totalCves: totalCves,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                lowCount: lowCount,
                dependenciesScanned: dependencies.size()
            ])

            checkThresholdViolations(criticalCount, highCount, dependencies)

            return [
                status: exitCode == 0 ? "COMPLETED" : "COMPLETED_WITH_WARNINGS",
                correlationId: correlationId,
                scanTarget: scanTarget,
                durationMs: duration,
                exitCode: exitCode,
                totalCves: totalCves,
                criticalCount: criticalCount,
                highCount: highCount,
                mediumCount: mediumCount,
                lowCount: lowCount,
                dependenciesScanned: dependencies.size(),
                vulnerabilities: vulnerabilities,
                dependencies: dependencies
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("threshold")) throw e
            long duration = System.currentTimeMillis() - startTime
            String errMsg = "Dependency scan failed: ${e.message}"
            LoggingUtils.error("DependencyScanManager", errMsg, e)
            audit.emitAuditEvent("DEPENDENCY_SCAN_FAILED", errMsg, correlationId)
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected dependency scan error: ${e.message}"
            LoggingUtils.error("DependencyScanManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } finally {
            cleanupReportFile(reportFile)
        }
    }

    void setFailOnCritical(boolean value) {
        this.failOnCritical = value
    }

    void setFailOnHigh(boolean value) {
        this.failOnHigh = value
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    private List<String> buildScanCommand(String scanTarget, String reportFile, Map options) {
        List<String> args = []
        String tool = options.tool instanceof String ? options.tool.toString().toLowerCase() : "dependency-check"

        switch (tool) {
            case "dependency-check":
                String dcBinary = options.binaryPath instanceof String ?
                    options.binaryPath.toString() : "dependency-check"
                args.add(dcBinary)
                args.add("--format")
                args.add("JSON")
                args.add("--out")
                args.add(reportFile)
                args.add("--scan")
                args.add(scanTarget)
                if (options.exclude instanceof List) {
                    for (Object ex : (List) options.exclude) {
                        args.add("--exclude")
                        args.add(ex.toString())
                    }
                }
                if (options.suppressionFile instanceof String) {
                    args.add("--suppression")
                    args.add(options.suppressionFile.toString())
                }
                if (options.failOnCVSS instanceof Number) {
                    args.add("--failOnCVSS")
                    args.add(options.failOnCVSS.toString())
                }
                break

            default:
                args.add("dependency-check")
                args.add("--format")
                args.add("JSON")
                args.add("--out")
                args.add(reportFile)
                args.add("--scan")
                args.add(scanTarget)
                break
        }
        return args
    }

    private void configureFromOptions(Map options) {
        if (options.failOnCritical instanceof Boolean) {
            this.failOnCritical = (Boolean) options.failOnCritical
        }
        if (options.failOnHigh instanceof Boolean) {
            this.failOnHigh = (Boolean) options.failOnHigh
        }
    }

    private String readReportFile(String reportFile) {
        try {
            if (steps.fileExists(reportFile)) {
                return steps.readFile(file: reportFile, encoding: "UTF-8")
            }
            LoggingUtils.warn("DependencyScanManager",
                "Report file '${reportFile}' not found [correlationId=${correlationId}]")
            return null
        } catch (Exception e) {
            LoggingUtils.warn("DependencyScanManager",
                "Failed to read report file '${reportFile}': ${e.message}")
            return null
        }
    }

    private void cleanupReportFile(String reportFile) {
        try {
            if (steps.fileExists(reportFile)) {
                steps.sh(script: "rm -f '${reportFile}'", returnStatus: true)
            }
        } catch (Exception e) {
            LoggingUtils.warn("DependencyScanManager",
                "Failed to clean up report file '${reportFile}': ${e.message}")
        }
    }

    @NonCPS
    private Map parseReport(String json) {
        if (!ValidationUtils.isNonEmpty(json)) return null
        Map result = [
            vulnerabilities: [],
            dependencies: [],
            totalCves: 0,
            criticalCount: 0,
            highCount: 0,
            mediumCount: 0,
            lowCount: 0
        ]

        try {
            def parsed = new groovy.json.JsonSlurper().parseText(json)

            if (parsed instanceof Map) {
                List deps = parsed.dependencies ?: []
                for (Object depObj : deps) {
                    if (!(depObj instanceof Map)) continue
                    Map dep = (Map) depObj

                    Map depEntry = [
                        fileName: dep.fileName?.toString() ?: "",
                        filePath: dep.filePath?.toString() ?: "",
                        md5: dep.md5?.toString() ?: "",
                        sha1: dep.sha1?.toString() ?: "",
                        sha256: dep.sha256?.toString() ?: ""
                    ]
                    result.dependencies.add(depEntry)

                    List vulns = dep.vulnerabilities ?: []
                    for (Object vObj : vulns) {
                        if (!(vObj instanceof Map)) continue
                        Map v = (Map) vObj
                        String severity = resolveSeverity(v)
                        Map vulnEntry = [
                            name: v.name?.toString() ?: "",
                            cve: extractCveId(v),
                            severity: severity,
                            cvssScore: extractCvssScore(v),
                            description: v.description?.toString() ?: "",
                            solution: extractSolution(v),
                            vulnerableSoftware: extractVulnerableSoftware(v),
                            references: extractReferences(v)
                        ]
                        result.vulnerabilities.add(vulnEntry)

                        switch (severity) {
                            case "CRITICAL": result.criticalCount++; break
                            case "HIGH": result.highCount++; break
                            case "MEDIUM": result.mediumCount++; break
                            default: result.lowCount++; break
                        }
                    }
                }
            }

            result.totalCves = result.vulnerabilities.size()

            if (result.totalCves > MAX_REPORTED_CVES) {
                result.vulnerabilities = result.vulnerabilities.take(MAX_REPORTED_CVES)
            }

            return result

        } catch (Exception e) {
            LoggingUtils.warn("DependencyScanManager",
                "Failed to parse dependency scan report: ${e.message}")
            return result
        }
    }

    @NonCPS
    private String resolveSeverity(Map vuln) {
        String cvssSource = "CVSS_SOURCE"
        Object severityRaw = vuln.get("severity")
        if (severityRaw instanceof String) {
            String s = severityRaw.toString().toUpperCase()
            if (SEVERITY_ORDER.contains(s)) return s
        }

        Object cvssRaw = vuln.get("cvssv3")
        if (cvssRaw instanceof Number) {
            double score = ((Number) cvssRaw).doubleValue()
            return scoreToSeverity(score)
        }
        cvssRaw = vuln.get("cvssScore")
        if (cvssRaw instanceof Number) {
            double score = ((Number) cvssRaw).doubleValue()
            return scoreToSeverity(score)
        }

        return "UNKNOWN"
    }

    @NonCPS
    private String scoreToSeverity(double score) {
        if (score >= 9.0) return "CRITICAL"
        if (score >= 7.0) return "HIGH"
        if (score >= 4.0) return "MEDIUM"
        return "LOW"
    }

    @NonCPS
    private String extractCveId(Map vuln) {
        String name = vuln.name?.toString() ?: ""
        if (name.matches("CVE-\\d{4}-\\d{4,}")) return name
        List cve = vuln.cve ?: []
        if (cve instanceof List && !cve.isEmpty()) {
            Object first = cve.get(0)
            if (first instanceof String) return (String) first
        }
        return name
    }

    @NonCPS
    private double extractCvssScore(Map vuln) {
        Object score = vuln.get("cvssScore")
        if (score instanceof Number) return ((Number) score).doubleValue()
        score = vuln.get("cvssv3")
        if (score instanceof Number) return ((Number) score).doubleValue()
        return 0.0
    }

    @NonCPS
    private String extractSolution(Map vuln) {
        String solution = vuln.solution?.toString() ?: ""
        if (ValidationUtils.isNonEmpty(solution)) return solution
        return vuln.remediation?.toString() ?: "No solution provided"
    }

    @NonCPS
    private List<String> extractVulnerableSoftware(Map vuln) {
        List software = vuln.vulnerableSoftware ?: []
        List<String> result = []
        if (software instanceof List) {
            for (Object s : software) {
                if (s != null) result.add(s.toString())
            }
        }
        return result
    }

    @NonCPS
    private List<String> extractReferences(Map vuln) {
        List refs = vuln.references ?: []
        List<String> result = []
        if (refs instanceof List) {
            for (Object r : refs) {
                if (r instanceof Map) {
                    String url = ((Map) r).url?.toString() ?: ""
                    if (ValidationUtils.isNonEmpty(url)) result.add(url)
                } else if (r instanceof String) {
                    result.add((String) r)
                }
            }
        }
        return result
    }

    @NonCPS
    private void checkThresholdViolations(int criticalCount, int highCount, List<Map> dependencies) {
        List<String> violations = []

        if (failOnCritical && criticalCount > 0) {
            violations.add("${criticalCount} critical CVE(s) found")
        }
        if (failOnHigh && highCount > 0) {
            violations.add("${highCount} high CVE(s) found")
        }

        if (!violations.isEmpty()) {
            String msg = "Dependency scan threshold exceeded: ${violations.join('; ')}"
            LoggingUtils.error("DependencyScanManager", msg, null)
            audit.emitAuditEvent("DEPENDENCY_THRESHOLD_EXCEEDED", msg, correlationId)
            telemetry.emitEvent("security.dependency", "threshold_exceeded", [
                correlationId: correlationId,
                criticalCount: criticalCount,
                highCount: highCount
            ])
            throw new RuntimeException(msg)
        }
    }

    @NonCPS
    private int resolveTimeout(Map options) {
        if (options.timeoutMs instanceof Number) {
            return ((Number) options.timeoutMs).intValue()
        }
        return 900000
    }
}
