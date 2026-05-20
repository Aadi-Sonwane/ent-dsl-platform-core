package com.enterprise.platform.branching

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class BranchGovernanceManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> PROTECTED_BRANCH_PATTERNS = [
        "^main$", "^master$", "^release/.*", "^hotfix/.*",
        "^develop$", "^trunk$", "^stable/.*"
    ]

    private static final List<String> PROHIBITED_PATTERNS = [
        "^\\.\\.", ".*/\.\\./.*", ".*\\*\\*", "^\\*", ".*//.*"
    ]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<Map> governanceViolations = []

    BranchGovernanceManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    BranchGovernanceManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map validateBranch(String branchName, Map branchGovernanceConfig) {
        LoggingUtils.info("BranchGovernanceManager",
            "Validating branch '${branchName}' against governance policy [correlationId=${correlationId}]")

        governanceViolations.clear()

        if (!ValidationUtils.isNonEmpty(branchName)) {
            governanceViolations.add([
                type: "EMPTY_BRANCH_NAME",
                severity: "ERROR",
                message: "Branch name must not be null or empty"
            ])
            return buildResult(false)
        }

        String normalizedBranch = normalizeBranchName(branchName)
        LoggingUtils.info("BranchGovernanceManager",
            "Normalized branch: '${normalizedBranch}' [correlationId=${correlationId}]")

        try {
            if (branchGovernanceConfig == null) branchGovernanceConfig = [:]

            List<String> includePatterns = resolveIncludePatterns(branchGovernanceConfig)
            List<String> excludePatterns = resolveExcludePatterns(branchGovernanceConfig)

            boolean matchesInclude = matchesAnyPattern(normalizedBranch, includePatterns)
            boolean matchesExclude = matchesAnyPattern(normalizedBranch, excludePatterns)

            if (matchesExclude) {
                governanceViolations.add([
                    type: "BRANCH_EXCLUDED",
                    severity: "ERROR",
                    branch: normalizedBranch,
                    message: "Branch '${normalizedBranch}' matches an exclude pattern and is not permitted for CI execution"
                ])
                LoggingUtils.error("BranchGovernanceManager",
                    "Branch '${normalizedBranch}' excluded by governance policy", null)
                return buildResult(false)
            }

            if (!matchesInclude) {
                governanceViolations.add([
                    type: "BRANCH_NOT_INCLUDED",
                    severity: "ERROR",
                    branch: normalizedBranch,
                    message: "Branch '${normalizedBranch}' does not match any include pattern and is not authorized"
                ])
                LoggingUtils.error("BranchGovernanceManager",
                    "Branch '${normalizedBranch}' not in include patterns", null)
                return buildResult(false)
            }

            boolean isProtected = isProtectedBranch(normalizedBranch)
            if (isProtected) {
                audit.emitAuditEvent("PROTECTED_BRANCH_EXECUTION",
                    "Protected branch '${normalizedBranch}' is executing pipeline (governance bypass allowed for CI)", correlationId)
                LoggingUtils.info("BranchGovernanceManager",
                    "Branch '${normalizedBranch}' is a protected branch [correlationId=${correlationId}]")
            }

            LoggingUtils.info("BranchGovernanceManager",
                "Branch '${normalizedBranch}' passed governance validation [correlationId=${correlationId}]")

            audit.emitAuditEvent("BRANCH_GOVERNANCE_PASSED",
                "Branch '${normalizedBranch}' passed governance validation", correlationId)
            telemetry.emitEvent("branching.governance", "passed", [
                correlationId: correlationId,
                branch: normalizedBranch,
                isProtected: isProtected,
                matchedPattern: findMatchedIncludePattern(normalizedBranch, includePatterns)
            ])

            return buildResult(true, isProtected, normalizedBranch)

        } catch (Exception e) {
            governanceViolations.add([
                type: "GOVERNANCE_ERROR",
                severity: "ERROR",
                branch: normalizedBranch,
                message: "Branch governance validation error: ${e.message}"
            ])
            LoggingUtils.error("BranchGovernanceManager",
                "Branch governance error for '${normalizedBranch}': ${e.message}", e)
            return buildResult(false)
        }
    }

    boolean isProtectedBranch(String branchName) {
        if (!ValidationUtils.isNonEmpty(branchName)) return false
        String normalized = normalizeBranchName(branchName)
        for (String pattern : PROTECTED_BRANCH_PATTERNS) {
            if (normalized.matches(pattern)) return true
        }
        return false
    }

    List<Map> getGovernanceViolations() {
        return new ArrayList<>(governanceViolations)
    }

    boolean hasViolations() {
        return !governanceViolations.isEmpty()
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String normalizeBranchName(String branchName) {
        if (branchName == null) return ""
        String normalized = branchName.trim()
        if (normalized.startsWith("origin/")) {
            normalized = normalized.substring(7)
        }
        if (normalized.startsWith("refs/heads/")) {
            normalized = normalized.substring(11)
        }
        if (normalized.startsWith("refs/remotes/")) {
            normalized = normalized.substring(13)
            if (normalized.contains("/")) {
                normalized = normalized.substring(normalized.indexOf("/") + 1)
            }
        }
        return normalized
    }

    @NonCPS
    private List<String> resolveIncludePatterns(Map config) {
        List<String> patterns = []
        Object includeRaw = config.get("include")
        if (includeRaw instanceof List) {
            for (Object item : (List) includeRaw) {
                if (item instanceof String && ValidationUtils.isNonEmpty((String) item)) {
                    String pattern = item.toString().trim()
                    if (validateRegexPattern(pattern)) {
                        patterns.add(pattern)
                    } else {
                        LoggingUtils.warn("BranchGovernanceManager",
                            "Invalid include regex pattern '${pattern}' skipped")
                    }
                }
            }
        } else if (includeRaw instanceof String && ValidationUtils.isNonEmpty((String) includeRaw)) {
            String pattern = includeRaw.toString().trim()
            if (validateRegexPattern(pattern)) {
                patterns.add(pattern)
            }
        }
        if (patterns.isEmpty()) {
            patterns.add(".*")
        }
        return patterns
    }

    @NonCPS
    private List<String> resolveExcludePatterns(Map config) {
        List<String> patterns = []
        Object excludeRaw = config.get("exclude")
        if (excludeRaw instanceof List) {
            for (Object item : (List) excludeRaw) {
                if (item instanceof String && ValidationUtils.isNonEmpty((String) item)) {
                    String pattern = item.toString().trim()
                    if (validateRegexPattern(pattern)) {
                        patterns.add(pattern)
                    }
                }
            }
        } else if (excludeRaw instanceof String && ValidationUtils.isNonEmpty((String) excludeRaw)) {
            String pattern = excludeRaw.toString().trim()
            if (validateRegexPattern(pattern)) {
                patterns.add(pattern)
            }
        }
        return patterns
    }

    @NonCPS
    private boolean matchesAnyPattern(String value, List<String> patterns) {
        if (value == null || patterns == null) return false
        for (String pattern : patterns) {
            try {
                if (value.matches(pattern)) return true
            } catch (Exception e) {
                LoggingUtils.warn("BranchGovernanceManager",
                    "Regex match error for pattern '${pattern}': ${e.message}")
            }
        }
        return false
    }

    @NonCPS
    private String findMatchedIncludePattern(String value, List<String> patterns) {
        if (value == null || patterns == null) return ""
        for (String pattern : patterns) {
            try {
                if (value.matches(pattern)) return pattern
            } catch (Exception e) { }
        }
        return ""
    }

    @NonCPS
    private boolean validateRegexPattern(String pattern) {
        if (!ValidationUtils.isNonEmpty(pattern)) return false
        for (String prohibited : PROHIBITED_PATTERNS) {
            if (pattern.matches(prohibited)) {
                LoggingUtils.warn("BranchGovernanceManager",
                    "Pattern '${pattern}' appears potentially dangerous and has been rejected")
                return false
            }
        }
        try {
            java.util.regex.Pattern.compile(pattern)
            return true
        } catch (java.util.regex.PatternSyntaxException e) {
            return false
        }
    }

    @NonCPS
    private Map buildResult(boolean authorized, boolean isProtected, String branchName) {
        return [
            authorized: authorized,
            isProtected: isProtected,
            branchName: branchName,
            violations: new ArrayList<>(governanceViolations)
        ]
    }

    @NonCPS
    private Map buildResult(boolean authorized) {
        return [
            authorized: authorized,
            isProtected: false,
            branchName: "",
            violations: new ArrayList<>(governanceViolations)
        ]
    }
}
