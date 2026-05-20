package com.enterprise.platform.security

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class ComplianceValidationManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String DEFAULT_LICENSE_POLICY_PATH = "resources/policies/license-governance-policy.yml"
    private static final List<String> RESTRICTED_LICENSES = [
        "GPL-2.0", "GPL-3.0", "AGPL-1.0", "AGPL-3.0",
        "SSPL-1.0", "BUSL-1.1"
    ]

    private static final List<String> ALLOWED_LICENSES = [
        "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause",
        "LGPL-2.1", "LGPL-3.0", "MPL-2.0", "CDDL-1.0",
        "EPL-1.0", "EPL-2.0", "ISC", "Unlicense"
    ]

    private static final List<String> RESTRICTED_CRYPTO = [
        "MD4", "MDC2", "RC2", "RC4", "RC5", "DES",
        "SSLv2", "SSLv3", "TLSv1.0", "TLSv1.1"
    ]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<Map> complianceViolations = []
    private final List<Map> complianceWarnings = []

    ComplianceValidationManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    ComplianceValidationManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map validateCompliance(Map projectConfig, Map scanResults) {
        LoggingUtils.info("ComplianceValidationManager",
            "Starting compliance validation [correlationId=${correlationId}]")
        complianceViolations.clear()
        complianceWarnings.clear()

        if (projectConfig == null) projectConfig = [:]
        if (scanResults == null) scanResults = [:]

        long startTime = System.currentTimeMillis()

        try {
            List<Map> results = []

            Map licenseResult = validateLicenseCompliance(projectConfig, scanResults)
            results.addAll(licenseResult.results ?: [])

            Map cryptoResult = validateCryptographicStandards(projectConfig, scanResults)
            results.addAll(cryptoResult.results ?: [])

            Map exportControlResult = validateExportControlCompliance(projectConfig)
            results.addAll(exportControlResult.results ?: [])

            Map securityPolicyResult = validateSecurityPolicyCompliance(projectConfig, scanResults)
            results.addAll(securityPolicyResult.results ?: [])

            Map artifactGovernanceResult = validateArtifactGovernance(projectConfig)
            results.addAll(artifactGovernanceResult.results ?: [])

            Map dataGovernanceResult = validateDataGovernance(projectConfig)
            results.addAll(dataGovernanceResult.results ?: [])

            long duration = System.currentTimeMillis() - startTime
            int violations = countSeverity(results, "VIOLATION")
            int warnings = countSeverity(results, "WARNING")

            Map complianceReport = buildComplianceReport(results, violations, warnings, duration)

            LoggingUtils.info("ComplianceValidationManager",
                "Compliance validation completed in ${duration}ms: ${violations} violations, ${warnings} warnings [correlationId=${correlationId}]")

            audit.emitAuditEvent("COMPLIANCE_VALIDATION_COMPLETED",
                "Compliance validation: ${violations} violations, ${warnings} warnings", correlationId)
            telemetry.emitEvent("security.compliance", "validation_completed", [
                correlationId: correlationId,
                durationMs: duration,
                totalChecks: results.size(),
                violations: violations,
                warnings: warnings,
                overallStatus: complianceReport.status
            ])

            if (violations > 0) {
                String msg = "Compliance validation failed with ${violations} violation(s)"
                LoggingUtils.error("ComplianceValidationManager", msg, null)
                audit.emitAuditEvent("COMPLIANCE_VALIDATION_FAILED", msg, correlationId)
                telemetry.emitEvent("security.compliance", "validation_failed", [
                    correlationId: correlationId,
                    violations: violations
                ])
                throw new RuntimeException(msg)
            }

            return complianceReport

        } catch (RuntimeException e) {
            if (e.message.contains("Compliance validation failed")) throw e
            String errMsg = "Compliance validation error: ${e.message}"
            LoggingUtils.error("ComplianceValidationManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected compliance validation error: ${e.message}"
            LoggingUtils.error("ComplianceValidationManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * License compliance
     */

    private Map validateLicenseCompliance(Map projectConfig, Map scanResults) {
        List<Map> results = []

        Map securityConfig = extractMapField(projectConfig, "security")
        Map licenseConfig = securityConfig != null ? extractMapField(securityConfig, "license") : null

        Boolean licenseEnabled = licenseConfig != null ?
            (licenseConfig.enabled instanceof Boolean ? (Boolean) licenseConfig.enabled : true) : true
        if (!licenseEnabled) {
            results.add(buildResult("LICENSE_CHECK", "SKIPPED", "License compliance check disabled by configuration"))
            return [results: results]
        }

        List<String> allowedLicenses = licenseConfig?.allowedLicenses instanceof List ?
            (List<String>) licenseConfig.allowedLicenses : ALLOWED_LICENSES
        List<String> restrictedLicenses = licenseConfig?.restrictedLicenses instanceof List ?
            (List<String>) licenseConfig.restrictedLicenses : RESTRICTED_LICENSES

        List<Map> dependencyList = scanResults.dependencies instanceof List ?
            (List<Map>) scanResults.dependencies : []
        if (dependencyList.isEmpty()) {
            dependencyList = scanResults.dependenciesScanned instanceof List ?
                (List<Map>) scanResults.dependenciesScanned : []
        }

        if (dependencyList.isEmpty()) {
            results.add(buildResult("LICENSE_CHECK", "PASSED",
                "No dependencies to validate against license policy"))
            return [results: results]
        }

        Map<String, Object> licenseMap = extractMapField(scanResults, "licenses")
        if (licenseMap == null) {
            licenseMap = extractMapField(scanResults, "licenseSummary")
        }

        if (licenseMap != null) {
            for (Map.Entry entry : licenseMap.entrySet()) {
                String depName = entry.key.toString()
                Object licenseValue = entry.value
                String license = licenseValue instanceof String ?
                    (String) licenseValue : licenseValue?.toString() ?: "UNKNOWN"

                if (restrictedLicenses.contains(license)) {
                    complianceViolations.add([
                        type: "RESTRICTED_LICENSE",
                        dependency: depName,
                        license: license,
                        message: "Dependency '${depName}' uses restricted license '${license}'"
                    ])
                    results.add(buildResult("LICENSE_CHECK", "VIOLATION",
                        "Dependency '${depName}' uses restricted license '${license}'", [
                            dependency: depName, license: license
                        ]))
                } else if (!allowedLicenses.contains(license) && !"UNKNOWN".equals(license)) {
                    complianceWarnings.add([
                        type: "UNRECOGNIZED_LICENSE",
                        dependency: depName,
                        license: license
                    ])
                    results.add(buildResult("LICENSE_CHECK", "WARNING",
                        "Dependency '${depName}' uses unrecognized license '${license}'", [
                            dependency: depName, license: license
                        ]))
                } else if ("UNKNOWN".equals(license)) {
                    complianceWarnings.add([
                        type: "UNKNOWN_LICENSE",
                        dependency: depName
                    ])
                    results.add(buildResult("LICENSE_CHECK", "WARNING",
                        "Dependency '${depName}' has unknown license", [
                            dependency: depName
                        ]))
                }
            }
        } else {
            results.add(buildResult("LICENSE_CHECK", "PASSED",
                "No license data available for validation"))
        }

        return [results: results]
    }

    /*
     * Cryptographic standards
     */

    private Map validateCryptographicStandards(Map projectConfig, Map scanResults) {
        List<Map> results = []

        List<Map> vulns = scanResults.vulnerabilities instanceof List ?
            (List<Map>) scanResults.vulnerabilities : []

        List<String> cryptoViolations = []
        List<Map> trivyResults = scanResults.trivyResults instanceof List ?
            (List<Map>) scanResults.trivyResults : []

        for (Map vuln : vulns) {
            String title = vuln.title?.toString() ?: ""
            String description = vuln.description?.toString() ?: ""
            String combined = title + " " + description
            for (String restricted : RESTRICTED_CRYPTO) {
                if (combined.contains(restricted)) {
                    cryptoViolations.add(restricted)
                }
            }
        }

        if (!cryptoViolations.isEmpty()) {
            String violList = cryptoViolations.toUnique().join(", ")
            complianceViolations.add([
                type: "RESTRICTED_CRYPTO",
                algorithms: cryptoViolations.toUnique(),
                message: "Restricted cryptographic algorithm(s) detected: ${violList}"
            ])
            results.add(buildResult("CRYPTO_STANDARDS", "VIOLATION",
                "Restricted cryptographic algorithm(s) detected: ${violList}", [
                    algorithms: cryptoViolations.toUnique()
                ]))
        } else {
            results.add(buildResult("CRYPTO_STANDARDS", "PASSED",
                "No restricted cryptographic algorithms detected"))
        }

        return [results: results]
    }

    /*
     * Export control
     */

    private Map validateExportControlCompliance(Map projectConfig) {
        List<Map> results = []

        Map securityConfig = extractMapField(projectConfig, "security")
        Map exportConfig = securityConfig != null ? extractMapField(securityConfig, "exportControl") : null

        if (exportConfig != null) {
            Boolean encryptionExport = exportConfig.encryptionExport instanceof Boolean ?
                (Boolean) exportConfig.encryptionExport : false
            String jurisdiction = exportConfig.jurisdiction instanceof String ?
                exportConfig.jurisdiction.toString() : ""

            if (encryptionExport) {
                complianceWarnings.add([
                    type: "ENCRYPTION_EXPORT",
                    message: "Project uses encryption functionality which may require export compliance review"
                ])
                results.add(buildResult("EXPORT_CONTROL", "WARNING",
                    "Encryption functionality declared — may require export compliance review", [
                        jurisdiction: jurisdiction
                    ]))
            } else {
                results.add(buildResult("EXPORT_CONTROL", "PASSED",
                    "No export control concerns detected"))
            }
        } else {
            results.add(buildResult("EXPORT_CONTROL", "PASSED",
                "No export control configuration found, assuming standard compliance"))
        }

        return [results: results]
    }

    /*
     * Security policy
     */

    private Map validateSecurityPolicyCompliance(Map projectConfig, Map scanResults) {
        List<Map> results = []

        int criticalVulns = scanResults.criticalCount instanceof Number ?
            ((Number) scanResults.criticalCount).intValue() : 0
        int highVulns = scanResults.highCount instanceof Number ?
            ((Number) scanResults.highCount).intValue() : 0
        int totalVulns = scanResults.totalVulnerabilities instanceof Number ?
            ((Number) scanResults.totalVulnerabilities).intValue() : 0

        Map securityConfig = extractMapField(projectConfig, "security")
        Map policyConfig = extractMapField(securityConfig, "policy")

        int criticalThreshold = 0
        int highThreshold = 5
        int totalThreshold = 50

        if (policyConfig != null) {
            if (policyConfig.criticalThreshold instanceof Number) {
                criticalThreshold = ((Number) policyConfig.criticalThreshold).intValue()
            }
            if (policyConfig.highThreshold instanceof Number) {
                highThreshold = ((Number) policyConfig.highThreshold).intValue()
            }
            if (policyConfig.totalThreshold instanceof Number) {
                totalThreshold = ((Number) policyConfig.totalThreshold).intValue()
            }
        }

        if (criticalVulns > criticalThreshold) {
            complianceViolations.add([
                type: "SECURITY_POLICY_CRITICAL",
                count: criticalVulns,
                threshold: criticalThreshold
            ])
            results.add(buildResult("SECURITY_POLICY", "VIOLATION",
                "${criticalVulns} critical vulnerabilities exceed threshold of ${criticalThreshold}"))
        }
        if (highVulns > highThreshold) {
            complianceViolations.add([
                type: "SECURITY_POLICY_HIGH",
                count: highVulns,
                threshold: highThreshold
            ])
            results.add(buildResult("SECURITY_POLICY", "VIOLATION",
                "${highVulns} high vulnerabilities exceed threshold of ${highThreshold}"))
        }
        if (totalVulns > totalThreshold) {
            complianceWarnings.add([
                type: "SECURITY_POLICY_TOTAL",
                count: totalVulns,
                threshold: totalThreshold
            ])
            results.add(buildResult("SECURITY_POLICY", "WARNING",
                "${totalVulns} total vulnerabilities exceed threshold of ${totalThreshold}"))
        }

        if (results.isEmpty()) {
            results.add(buildResult("SECURITY_POLICY", "PASSED",
                "Security policy thresholds not exceeded"))
        }

        return [results: results]
    }

    /*
     * Artifact governance
     */

    private Map validateArtifactGovernance(Map projectConfig) {
        List<Map> results = []

        Boolean signedArtifacts = false
        Boolean sbomGenerated = false
        String artifactRepository = ""

        Map securityConfig = extractMapField(projectConfig, "security")
        if (securityConfig != null) {
            Map signingConfig = extractMapField(securityConfig, "signing")
            if (signingConfig != null) {
                signedArtifacts = signingConfig.enabled instanceof Boolean ?
                    (Boolean) signingConfig.enabled : false
            }
            Map sbomConfig = extractMapField(securityConfig, "sbom")
            if (sbomConfig != null) {
                sbomGenerated = sbomConfig.enabled instanceof Boolean ?
                    (Boolean) sbomConfig.enabled : false
            }
        }

        if (!signedArtifacts) {
            complianceWarnings.add([
                type: "ARTIFACT_SIGNING",
                message: "Artifact signing is not enabled"
            ])
            results.add(buildResult("ARTIFACT_GOVERNANCE", "WARNING",
                "Artifact signing is not enabled — production releases should be signed"))
        }
        if (!sbomGenerated) {
            complianceWarnings.add([
                type: "SBOM_GENERATION",
                message: "SBOM generation is not enabled"
            ])
            results.add(buildResult("ARTIFACT_GOVERNANCE", "WARNING",
                "SBOM generation is not enabled — software supply chain transparency required"))
        }

        if (results.isEmpty()) {
            results.add(buildResult("ARTIFACT_GOVERNANCE", "PASSED",
                "Artifact governance requirements satisfied"))
        }

        return [results: results]
    }

    /*
     * Data governance
     */

    private Map validateDataGovernance(Map projectConfig) {
        List<Map> results = []

        Map dataConfig = extractMapField(projectConfig, "dataGovernance")

        if (dataConfig != null && !dataConfig.isEmpty()) {
            Boolean containsPii = dataConfig.containsPii instanceof Boolean ?
                (Boolean) dataConfig.containsPii : false
            String dataSensitivity = dataConfig.sensitivityLevel instanceof String ?
                dataConfig.sensitivityLevel.toString() : "internal"

            if (containsPii) {
                complianceViolations.add([
                    type: "PII_DATA",
                    sensitivity: dataSensitivity,
                    message: "Project contains PII data which requires additional compliance controls"
                ])
                results.add(buildResult("DATA_GOVERNANCE", "VIOLATION",
                    "PII data detected — additional compliance controls required", [
                        sensitivity: dataSensitivity
                    ]))
            } else {
                results.add(buildResult("DATA_GOVERNANCE", "PASSED",
                    "No PII data concerns"))
            }
        } else {
            results.add(buildResult("DATA_GOVERNANCE", "PASSED",
                "No data governance configuration found"))
        }

        return [results: results]
    }

    /*
     * Reporting
     */

    @NonCPS
    private Map buildComplianceReport(List<Map> results, int violations, int warnings, long duration) {
        String overallStatus = violations > 0 ? "FAILED" : (warnings > 0 ? "WARNINGS" : "PASSED")

        Map report = [:]
        report["status"] = overallStatus
        report["correlationId"] = correlationId
        report["durationMs"] = duration
        report["totalChecks"] = results.size()
        report["violations"] = violations
        report["warnings"] = warnings
        report["passed"] = countStatus(results, "PASSED")
        report["skipped"] = countStatus(results, "SKIPPED")
        report["checkResults"] = results

        String timestamp
        try {
            timestamp = new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
        } catch (Exception e) {
            timestamp = ""
        }
        report["timestamp"] = timestamp

        List<Map> violationDetails = []
        for (Map v : complianceViolations) {
            violationDetails.add([
                type: v.type,
                message: v.message,
                details: v.findAll { k, val -> k != "type" && k != "message" }
            ])
        }
        report["violationDetails"] = violationDetails

        List<Map> warningDetails = []
        for (Map w : complianceWarnings) {
            warningDetails.add([
                type: w.type,
                message: w.message,
                details: w.findAll { k, val -> k != "type" && k != "message" }
            ])
        }
        report["warningDetails"] = warningDetails

        return report
    }

    @NonCPS
    private Map buildResult(String check, String status, String message) {
        return buildResult(check, status, message, [:])
    }

    @NonCPS
    private Map buildResult(String check, String status, String message, Map details) {
        Map result = [:]
        result["check"] = check
        result["status"] = status
        result["message"] = message
        if (details != null && !details.isEmpty()) {
            result["details"] = details
        }
        return result
    }

    @NonCPS
    private int countSeverity(List<Map> results, String severity) {
        int count = 0
        for (Map r : results) {
            if (severity.equals(r.status)) count++
        }
        return count
    }

    @NonCPS
    private int countStatus(List<Map> results, String status) {
        return countSeverity(results, status)
    }

    @NonCPS
    private Map extractMapField(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof Map) return (Map) value
        return null
    }
}
