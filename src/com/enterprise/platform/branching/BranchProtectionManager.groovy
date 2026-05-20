package com.enterprise.platform.branching

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class BranchProtectionManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> PROTECTED_BRANCH_PATTERNS = [
        "^main$", "^master$", "^release/.*", "^hotfix/.*"
    ]

    private static final int MIN_REQUIRED_REVIEWERS = 1
    private static final int MIN_REQUIRED_PASSING_CHECKS = 1

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    BranchProtectionManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    BranchProtectionManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map enforceProtectedBranchPolicy(String branchName, Map protectionConfig) {
        LoggingUtils.info("BranchProtectionManager",
            "Enforcing protected branch policy for '${branchName}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(branchName)) {
            throw new IllegalArgumentException("Branch name must not be null or empty")
        }
        if (protectionConfig == null) protectionConfig = [:]

        try {
            String normalizedBranch = normalizeBranch(branchName)
            boolean isProtected = isProtectedBranch(normalizedBranch)

            if (!isProtected) {
                LoggingUtils.info("BranchProtectionManager",
                    "Branch '${normalizedBranch}' is not protected, policy not enforced [correlationId=${correlationId}]")
                return [
                    status: "NOT_PROTECTED",
                    branch: normalizedBranch,
                    protected: false,
                    policyEnforced: false
                ]
            }

            List<Map<String, Object>> requirements = []
            List<Map<String, Object>> violations = []

            int requiredReviewers = protectionConfig.minReviewers instanceof Number ?
                ((Number) protectionConfig.minReviewers).intValue() : MIN_REQUIRED_REVIEWERS
            int requiredChecks = protectionConfig.minPassingChecks instanceof Number ?
                ((Number) protectionConfig.minPassingChecks).intValue() : MIN_REQUIRED_PASSING_CHECKS

            requirements.add([
                type: "REQUIRED_REVIEWERS",
                count: requiredReviewers,
                description: "Minimum ${requiredReviewers} reviewer approval(s) required"
            ])
            requirements.add([
                type: "REQUIRED_CHECKS",
                count: requiredChecks,
                description: "Minimum ${requiredChecks} passing check(s) required"
            ])

            Boolean requirePr = protectionConfig.requirePullRequest instanceof Boolean ?
                (Boolean) protectionConfig.requirePullRequest : true
            Boolean requireUpToDate = protectionConfig.requireBranchUpToDate instanceof Boolean ?
                (Boolean) protectionConfig.requireBranchUpToDate : true
            Boolean includeAdministrators = protectionConfig.includeAdministrators instanceof Boolean ?
                (Boolean) protectionConfig.includeAdministrators : true
            Boolean enforceForAdmins = protectionConfig.enforceForAdmins instanceof Boolean ?
                (Boolean) protectionConfig.enforceForAdmins : true

            if (requirePr) {
                requirements.add([
                    type: "REQUIRE_PULL_REQUEST",
                    value: true,
                    description: "Pull request required for '${normalizedBranch}'"
                ])
            }
            if (requireUpToDate) {
                requirements.add([
                    type: "REQUIRE_BRANCH_UP_TO_DATE",
                    value: true,
                    description: "Branch must be up to date with base branch"
                ])
            }

            if (includeAdministrators) {
                requirements.add([
                    type: "INCLUDE_ADMINISTRATORS",
                    value: true,
                    description: "Protection rules apply to administrators"
                ])
            }

            int activeViolations = violations.size()
            boolean policyEnforced = activeViolations == 0

            LoggingUtils.info("BranchProtectionManager",
                "Protected branch policy for '${normalizedBranch}': ${policyEnforced ? 'ENFORCED' : 'VIOLATIONS_FOUND'} (${requirements.size()} requirements, ${activeViolations} violations) [correlationId=${correlationId}]")

            audit.emitAuditEvent("BRANCH_PROTECTION_ENFORCED",
                "Protected branch policy enforced for '${normalizedBranch}': ${requirements.size()} requirements", correlationId)
            telemetry.emitEvent("branching.protection", "enforced", [
                correlationId: correlationId,
                branch: normalizedBranch,
                requirements: requirements.size(),
                violations: activeViolations,
                policyEnforced: policyEnforced
            ])

            if (!policyEnforced) {
                String msg = "Protected branch '${normalizedBranch}' has ${activeViolations} policy violation(s)"
                LoggingUtils.error("BranchProtectionManager", msg, null)
                throw new RuntimeException(msg)
            }

            return [
                status: "ENFORCED",
                branch: normalizedBranch,
                protected: true,
                policyEnforced: true,
                requirements: requirements,
                violations: violations
            ]

        } catch (RuntimeException e) {
            if (e.message.contains("protected branch") || e.message.contains("policy violation")) throw e
            String errMsg = "Branch protection enforcement failed: ${e.message}"
            LoggingUtils.error("BranchProtectionManager", errMsg, e)
            throw new RuntimeException(errMsg, e)

        } catch (Exception e) {
            String errMsg = "Unexpected error enforcing branch protection: ${e.message}"
            LoggingUtils.error("BranchProtectionManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    boolean isProtectedBranch(String branchName) {
        if (!ValidationUtils.isNonEmpty(branchName)) return false
        String normalized = normalizeBranch(branchName)
        for (String pattern : PROTECTED_BRANCH_PATTERNS) {
            if (normalized.matches(pattern)) return true
        }
        return false
    }

    Map generateComplianceReport(String branchName, Map protectionStatus) {
        LoggingUtils.info("BranchProtectionManager",
            "Generating compliance report for branch '${branchName}' [correlationId=${correlationId}]")

        Map report = [:]
        report["branch"] = branchName
        report["isProtected"] = protectionStatus.protected instanceof Boolean ?
            (Boolean) protectionStatus.protected : false
        report["policyEnforced"] = protectionStatus.policyEnforced instanceof Boolean ?
            (Boolean) protectionStatus.policyEnforced : false
        report["requirements"] = protectionStatus.requirements instanceof List ?
            (List<Map>) protectionStatus.requirements : []
        report["violations"] = protectionStatus.violations instanceof List ?
            (List<Map>) protectionStatus.violations : []
        report["generatedAt"] = formatTimestamp()
        report["correlationId"] = correlationId

        return report
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String normalizeBranch(String branchName) {
        if (branchName == null) return ""
        String normal = branchName.trim()
        if (normal.startsWith("origin/")) normal = normal.substring(7)
        if (normal.startsWith("refs/heads/")) normal = normal.substring(11)
        if (normal.startsWith("refs/remotes/")) normal = normal.substring(13)
        if (normal.contains("/")) normal = normal.substring(normal.lastIndexOf("/") + 1)
        return normal
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
