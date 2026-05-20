package com.enterprise.platform.quality

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class CodeCoverageManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final int DEFAULT_LINE_COVERAGE_THRESHOLD = 80
    private static final int DEFAULT_BRANCH_COVERAGE_THRESHOLD = 70
    private static final List<String> SUPPORTED_REPORT_TYPES = ["jacoco", "cobertura", "clover"]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    CodeCoverageManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    CodeCoverageManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map parseCoverageReport(String reportPath, String reportType) {
        LoggingUtils.info("CodeCoverageManager",
            "Parsing coverage report: type=${reportType}, path=${reportPath} [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(reportPath)) {
            throw new IllegalArgumentException("Coverage report path must not be null or empty")
        }
        String normalizedType = reportType != null ? reportType.trim().toLowerCase() : "jacoco"

        try {
            if (!steps.fileExists(reportPath)) {
                throw new RuntimeException("Coverage report not found: '${reportPath}'")
            }

            String reportContent = steps.readFile(file: reportPath, encoding: "UTF-8")
            if (!ValidationUtils.isNonEmpty(reportContent)) {
                throw new RuntimeException("Coverage report '${reportPath}' is empty")
            }

            Map parsed
            switch (normalizedType) {
                case "jacoco":
                    parsed = parseJacocoReport(reportContent)
                    break
                case "cobertura":
                    parsed = parseCoberturaReport(reportContent)
                    break
                case "clover":
                    parsed = parseCloverReport(reportContent)
                    break
                default:
                    LoggingUtils.warn("CodeCoverageManager",
                        "Unknown report type '${normalizedType}', attempting JaCoCo parsing [correlationId=${correlationId}]")
                    parsed = parseJacocoReport(reportContent)
                    break
            }

            LoggingUtils.info("CodeCoverageManager",
                "Coverage report parsed: line=${parsed.lineCoverage}%, branch=${parsed.branchCoverage}% [correlationId=${correlationId}]")
            audit.emitAuditEvent("COVERAGE_REPORT_PARSED",
                "Coverage report parsed: ${parsed.lineCoverage}% line, ${parsed.branchCoverage}% branch", correlationId)

            return parsed

        } catch (Exception e) {
            String errMsg = "Failed to parse coverage report '${reportPath}': ${e.message}"
            LoggingUtils.error("CodeCoverageManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map enforceCoverageThresholds(Map coverageData, Map thresholds) {
        LoggingUtils.info("CodeCoverageManager",
            "Enforcing coverage thresholds [correlationId=${correlationId}]")

        if (coverageData == null) {
            throw new IllegalArgumentException("Coverage data must not be null")
        }
        if (thresholds == null) thresholds = [:]

        double lineThreshold = thresholds.lineCoverage instanceof Number ?
            ((Number) thresholds.lineCoverage).doubleValue() : DEFAULT_LINE_COVERAGE_THRESHOLD
        double branchThreshold = thresholds.branchCoverage instanceof Number ?
            ((Number) thresholds.branchCoverage).doubleValue() : DEFAULT_BRANCH_COVERAGE_THRESHOLD

        double lineActual = coverageData.lineCoverage instanceof Number ?
            ((Number) coverageData.lineCoverage).doubleValue() : 0.0
        double branchActual = coverageData.branchCoverage instanceof Number ?
            ((Number) coverageData.branchCoverage).doubleValue() : 0.0

        List<Map<String, Object>> violations = []

        if (lineActual < lineThreshold) {
            violations.add([
                type: "LINE_COVERAGE_BELOW_THRESHOLD",
                metric: "lineCoverage",
                actual: lineActual,
                threshold: lineThreshold,
                message: "Line coverage ${String.format('%.1f', lineActual)}% is below threshold ${String.format('%.1f', lineThreshold)}%"
            ])
        }
        if (branchActual < branchThreshold) {
            violations.add([
                type: "BRANCH_COVERAGE_BELOW_THRESHOLD",
                metric: "branchCoverage",
                actual: branchActual,
                threshold: branchThreshold,
                message: "Branch coverage ${String.format('%.1f', branchActual)}% is below threshold ${String.format('%.1f', branchThreshold)}%"
            ])
        }

        boolean passed = violations.isEmpty()

        LoggingUtils.info("CodeCoverageManager",
            "Coverage threshold check: ${passed ? 'PASSED' : 'FAILED'} (line=${String.format('%.1f', lineActual)}/${String.format('%.1f', lineThreshold)}, branch=${String.format('%.1f', branchActual)}/${String.format('%.1f', branchThreshold)}) [correlationId=${correlationId}]")

        if (!passed) {
            for (Map v : violations) {
                audit.emitAuditEvent("COVERAGE_THRESHOLD_VIOLATION",
                    v.message as String, correlationId)
            }
            telemetry.emitEvent("quality.coverage", "threshold_violation", [
                correlationId: correlationId,
                violations: violations.size(),
                lineActual: lineActual,
                lineThreshold: lineThreshold,
                branchActual: branchActual,
                branchThreshold: branchThreshold
            ])
        }

        return [
            status: passed ? "PASSED" : "FAILED",
            lineCoverage: lineActual,
            branchCoverage: branchActual,
            lineThreshold: lineThreshold,
            branchThreshold: branchThreshold,
            violations: violations,
            passed: passed
        ]
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * JaCoCo CSV/XML parser
     */

    @NonCPS
    private Map parseJacocoReport(String content) {
        Map result = defaultCoverageResult()

        try {
            if (content.startsWith("<?xml") || content.startsWith("<report")) {
                return parseJacocoXml(content)
            }

            String[] lines = content.split("\\r?\\n")
            if (lines.length > 0 && lines[0].contains("CSV")) {
                return parseJacocoCsv(lines)
            }

            return result

        } catch (Exception e) {
            LoggingUtils.warn("CodeCoverageManager",
                "JaCoCo parsing failed, returning defaults: ${e.message}")
            return result
        }
    }

    @NonCPS
    private Map parseJacocoXml(String xml) {
        Map result = defaultCoverageResult()
        long totalLineMissed = 0
        long totalLineCovered = 0
        long totalBranchMissed = 0
        long totalBranchCovered = 0
        long totalInstructionsMissed = 0
        long totalInstructionsCovered = 0

        try {
            def parsed = new groovy.util.XmlSlurper(false, false).parseText(xml)

            parsed.package.each { pkg ->
                pkg.counter.each { counter ->
                    String type = counter.@type.text()
                    int missed = (counter.@missed.text() ?: "0") as int
                    int covered = (counter.@covered.text() ?: "0") as int

                    switch (type) {
                        case "LINE":
                            totalLineMissed += missed
                            totalLineCovered += covered
                            break
                        case "BRANCH":
                            totalBranchMissed += missed
                            totalBranchCovered += covered
                            break
                        case "INSTRUCTION":
                            totalInstructionsMissed += missed
                            totalInstructionsCovered += covered
                            break
                    }
                }
            }

            long totalLine = totalLineMissed + totalLineCovered
            long totalBranch = totalBranchMissed + totalBranchCovered
            long totalInstructions = totalInstructionsMissed + totalInstructionsCovered

            if (totalLine > 0) {
                result.lineCoverage = Math.round((totalLineCovered / (double) totalLine) * 10000.0) / 100.0
            }
            if (totalBranch > 0) {
                result.branchCoverage = Math.round((totalBranchCovered / (double) totalBranch) * 10000.0) / 100.0
            }
            if (totalInstructions > 0) {
                result.instructionCoverage = Math.round((totalInstructionsCovered / (double) totalInstructions) * 10000.0) / 100.0
            }

            result.lineTotal = totalLine
            result.lineCovered = totalLineCovered
            result.branchTotal = totalBranch
            result.branchCovered = totalBranchCovered

        } catch (Exception e) {
            LoggingUtils.warn("CodeCoverageManager",
                "JaCoCo XML parsing error: ${e.message}")
        }

        return result
    }

    @NonCPS
    private Map parseJacocoCsv(String[] lines) {
        Map result = defaultCoverageResult()

        try {
            long totalLineMissed = 0
            long totalLineCovered = 0
            long totalBranchMissed = 0
            long totalBranchCovered = 0

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim()
                if (!ValidationUtils.isNonEmpty(line)) continue

                String[] cols = line.split(",")
                if (cols.length < 7) continue

                String type = cols[2]?.trim()?.replaceAll('"', '')
                int missed = parseCsvInt(cols[3])
                int covered = parseCsvInt(cols[4])

                switch (type) {
                    case "LINE":
                        totalLineMissed += missed
                        totalLineCovered += covered
                        break
                    case "BRANCH":
                        totalBranchMissed += missed
                        totalBranchCovered += covered
                        break
                }
            }

            long totalLine = totalLineMissed + totalLineCovered
            long totalBranch = totalBranchMissed + totalBranchCovered

            if (totalLine > 0) {
                result.lineCoverage = Math.round((totalLineCovered / (double) totalLine) * 10000.0) / 100.0
            }
            if (totalBranch > 0) {
                result.branchCoverage = Math.round((totalBranchCovered / (double) totalBranch) * 10000.0) / 100.0
            }

            result.lineTotal = totalLine
            result.lineCovered = totalLineCovered
            result.branchTotal = totalBranch
            result.branchCovered = totalBranchCovered

        } catch (Exception e) {
            LoggingUtils.warn("CodeCoverageManager",
                "JaCoCo CSV parsing error: ${e.message}")
        }

        return result
    }

    /*
     * Cobertura parser
     */

    @NonCPS
    private Map parseCoberturaReport(String xml) {
        Map result = defaultCoverageResult()

        try {
            def parsed = new groovy.util.XmlSlurper(false, false).parseText(xml)

            double lineRate = parseDoubleAttr(parsed.@"lines-covered", parsed.@"lines-valid")
            double branchRate = parseDoubleAttr(parsed.@"branches-covered", parsed.@"branches-valid")

            if (lineRate >= 0) {
                result.lineCoverage = Math.round(lineRate * 10000.0) / 100.0
            }
            if (branchRate >= 0) {
                result.branchCoverage = Math.round(branchRate * 10000.0) / 100.0
            }

            result.lineTotal = parseLongAttr(parsed.@"lines-valid")
            result.lineCovered = parseLongAttr(parsed.@"lines-covered")
            result.branchTotal = parseLongAttr(parsed.@"branches-valid")
            result.branchCovered = parseLongAttr(parsed.@"branches-covered")

        } catch (Exception e) {
            LoggingUtils.warn("CodeCoverageManager",
                "Cobertura parsing error: ${e.message}")
        }

        return result
    }

    /*
     * Clover parser
     */

    @NonCPS
    private Map parseCloverReport(String xml) {
        Map result = defaultCoverageResult()

        try {
            def parsed = new groovy.util.XmlSlurper(false, false).parseText(xml)

            Object totals = parsed.project.metrics ?: parsed.metrics
            if (totals.isEmpty()) {
                totals = parsed.depthFirst().find { it.name() == "metrics" }
            }

            double lineCovered = 0
            double lineTotal = 0
            double branchCovered = 0
            double branchTotal = 0

            if (totals instanceof groovy.util.slurpersupport.NodeChildren) {
                totals.each { node ->
                    lineCovered += parseLongAttr(node.@"coveredstatements")
                    lineTotal += parseLongAttr(node.@"statements")
                    branchCovered += parseLongAttr(node.@"coveredconditionals")
                    branchTotal += parseLongAttr(node.@"conditionals")
                }
            }

            if (lineTotal > 0) {
                result.lineCoverage = Math.round((lineCovered / lineTotal) * 10000.0) / 100.0
            }
            if (branchTotal > 0) {
                result.branchCoverage = Math.round((branchCovered / branchTotal) * 10000.0) / 100.0
            }

            result.lineTotal = (long) lineTotal
            result.lineCovered = (long) lineCovered
            result.branchTotal = (long) branchTotal
            result.branchCovered = (long) branchCovered

        } catch (Exception e) {
            LoggingUtils.warn("CodeCoverageManager",
                "Clover parsing error: ${e.message}")
        }

        return result
    }

    /*
     * Helpers
     */

    @NonCPS
    private Map defaultCoverageResult() {
        return [
            lineCoverage: 0.0d,
            branchCoverage: 0.0d,
            instructionCoverage: 0.0d,
            lineTotal: 0L,
            lineCovered: 0L,
            lineMissed: 0L,
            branchTotal: 0L,
            branchCovered: 0L,
            branchMissed: 0L
        ]
    }

    @NonCPS
    private int parseCsvInt(String value) {
        if (value == null) return 0
        String cleaned = value.trim().replaceAll('"', '')
        try {
            return Integer.parseInt(cleaned)
        } catch (Exception e) {
            return 0
        }
    }

    @NonCPS
    private double parseDoubleAttr(Object attr1, Object attr2) {
        double val1 = 0
        double val2 = 0
        try {
            if (attr1 instanceof String) val1 = Double.parseDouble(attr1.toString())
            if (attr2 instanceof String) val2 = Double.parseDouble(attr2.toString())
        } catch (Exception e) { }
        if (val2 > 0) {
            return val1 / val2
        }
        return -1
    }

    @NonCPS
    private long parseLongAttr(Object attr) {
        if (attr == null) return 0
        try {
            return Long.parseLong(attr.toString())
        } catch (Exception e) {
            return 0
        }
    }
}
