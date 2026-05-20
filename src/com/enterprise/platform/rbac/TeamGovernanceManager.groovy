package com.enterprise.platform.rbac

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class TeamGovernanceManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> PROHIBITED_TEAM_NAME_PREFIXES = ["system-", "admin-", "root-", "jenkins-"]
    private static final int MAX_TEAM_NAME_LENGTH = 48
    private static final int MAX_TEAMS_PER_TENANT = 100

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    TeamGovernanceManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    TeamGovernanceManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Map validateTeamConfiguration(Map teamConfig) {
        LoggingUtils.info("TeamGovernanceManager",
            "Validating team configuration [correlationId=${correlationId}]")

        if (teamConfig == null) teamConfig = [:]

        List<Map<String, Object>> errors = []
        List<Map<String, Object>> warnings = []
        List<String> validatedTeams = []

        try {
            String businessUnit = teamConfig.businessUnit instanceof String ?
                teamConfig.businessUnit.toString() : ""
            String teamName = teamConfig.teamName instanceof String ?
                teamConfig.teamName.toString() : ""

            if (ValidationUtils.isNonEmpty(teamName)) {
                Map result = validateSingleTeam(teamName)
                errors.addAll(result.errors ?: [])
                warnings.addAll(result.warnings ?: [])
                if (result.valid instanceof Boolean && (Boolean) result.valid) {
                    validatedTeams.add(teamName)
                }
            }

            Object teamsRaw = teamConfig.get("teams")
            if (teamsRaw instanceof List) {
                List teams = (List) teamsRaw
                if (teams.size() > MAX_TEAMS_PER_TENANT) {
                    errors.add([
                        type: "TOO_MANY_TEAMS",
                        count: teams.size(),
                        limit: MAX_TEAMS_PER_TENANT,
                        message: "Team count ${teams.size()} exceeds maximum of ${MAX_TEAMS_PER_TENANT}"
                    ])
                }
                for (int i = 0; i < Math.min(teams.size(), MAX_TEAMS_PER_TENANT); i++) {
                    Object teamObj = teams.get(i)
                    if (teamObj instanceof String) {
                        Map result = validateSingleTeam((String) teamObj)
                        errors.addAll(result.errors ?: [])
                        warnings.addAll(result.warnings ?: [])
                        if (result.valid instanceof Boolean && (Boolean) result.valid) {
                            validatedTeams.add((String) teamObj)
                        }
                    } else if (teamObj instanceof Map) {
                        Map teamMap = (Map) teamObj
                        String name = teamMap.name instanceof String ? teamMap.name.toString() : ""
                        if (ValidationUtils.isNonEmpty(name)) {
                            Map result = validateSingleTeam(name)
                            errors.addAll(result.errors ?: [])
                            warnings.addAll(result.warnings ?: [])
                            if (result.valid instanceof Boolean && (Boolean) result.valid) {
                                validatedTeams.add(name)
                            }
                        }
                    }
                }
            }

            LoggingUtils.info("TeamGovernanceManager",
                "Team validation completed: ${validatedTeams.size()} valid teams, ${errors.size()} errors, ${warnings.size()} warnings [correlationId=${correlationId}]")

            audit.emitAuditEvent("TEAM_VALIDATION_COMPLETED",
                "Team configuration validated: ${validatedTeams.size()} valid teams", correlationId)

            return [
                valid: errors.isEmpty(),
                errors: errors,
                warnings: warnings,
                validatedTeams: validatedTeams,
                totalTeams: validatedTeams.size()
            ]

        } catch (Exception e) {
            String errMsg = "Team configuration validation failed: ${e.message}"
            LoggingUtils.error("TeamGovernanceManager", errMsg, e)
            return [
                valid: false,
                errors: [[type: "VALIDATION_ERROR", message: errMsg]],
                warnings: [],
                validatedTeams: []
            ]
        }
    }

    Map enforceNamespaceIsolation(String businessUnit, String teamName, String projectName) {
        LoggingUtils.info("TeamGovernanceManager",
            "Enforcing namespace isolation: BU=${businessUnit}, Team=${teamName}, Project=${projectName} [correlationId=${correlationId}]")

        List<Map<String, Object>> violations = []

        try {
            if (!ValidationUtils.isNonEmpty(businessUnit)) {
                violations.add([
                    type: "MISSING_BUSINESS_UNIT",
                    message: "Business unit is required for namespace isolation"
                ])
            }
            if (!ValidationUtils.isNonEmpty(teamName)) {
                violations.add([
                    type: "MISSING_TEAM_NAME",
                    message: "Team name is required for namespace isolation"
                ])
            }
            if (!ValidationUtils.isNonEmpty(projectName)) {
                violations.add([
                    type: "MISSING_PROJECT_NAME",
                    message: "Project name is required for namespace isolation"
                ])
            }

            String expectedNamespace = buildNamespace(businessUnit, teamName)
            String actualNamespace = buildNamespace(businessUnit, projectName)

            if (!violations.isEmpty()) {
                return [
                    isolated: false,
                    violations: violations,
                    expectedNamespace: expectedNamespace,
                    actualNamespace: actualNamespace
                ]
            }

            boolean isolated = expectedNamespace.equals(actualNamespace)

            if (!isolated) {
                violations.add([
                    type: "NAMESPACE_MISMATCH",
                    expected: expectedNamespace,
                    actual: actualNamespace,
                    message: "Project namespace '${actualNamespace}' does not match expected team namespace '${expectedNamespace}'"
                ])
            }

            LoggingUtils.info("TeamGovernanceManager",
                "Namespace isolation: ${isolated ? 'ISOLATED' : 'NOT_ISOLATED'} (expected=${expectedNamespace}, actual=${actualNamespace}) [correlationId=${correlationId}]")

            return [
                isolated: isolated,
                violations: violations,
                expectedNamespace: expectedNamespace,
                actualNamespace: actualNamespace
            ]

        } catch (Exception e) {
            String msg = "Namespace isolation check failed: ${e.message}"
            LoggingUtils.error("TeamGovernanceManager", msg, e)
            return [isolated: false, violations: [[type: "ISOLATION_ERROR", message: msg]]]
        }
    }

    Map validateCrossTeamAccess(String sourceTeam, String targetBusinessUnit, String targetTeam) {
        LoggingUtils.info("TeamGovernanceManager",
            "Validating cross-team access: ${sourceTeam} → ${targetBusinessUnit}/${targetTeam} [correlationId=${correlationId}]")

        List<Map<String, Object>> restrictions = []

        try {
            if (!ValidationUtils.isNonEmpty(sourceTeam)) {
                restrictions.add([type: "MISSING_SOURCE_TEAM", message: "Source team is required"])
            }
            if (!ValidationUtils.isNonEmpty(targetBusinessUnit)) {
                restrictions.add([type: "MISSING_TARGET_BU", message: "Target business unit is required"])
            }
            if (!ValidationUtils.isNonEmpty(targetTeam)) {
                restrictions.add([type: "MISSING_TARGET_TEAM", message: "Target team is required"])
            }

            if (!restrictions.isEmpty()) {
                return [permitted: false, restrictions: restrictions]
            }

            boolean sameBu = targetBusinessUnit.equalsIgnoreCase(
                extractBusinessUnitFromTeam(sourceTeam))
            boolean sameTeam = sourceTeam.equalsIgnoreCase(targetTeam)

            boolean permitted = sameBu || sameTeam

            if (!permitted) {
                restrictions.add([
                    type: "CROSS_TEAM_ACCESS_DENIED",
                    source: sourceTeam,
                    target: "${targetBusinessUnit}/${targetTeam}",
                    message: "Cross-team access from '${sourceTeam}' to '${targetBusinessUnit}/${targetTeam}' is not permitted without explicit authorization"
                ])
            }

            LoggingUtils.info("TeamGovernanceManager",
                "Cross-team access: ${permitted ? 'PERMITTED' : 'DENIED'} [correlationId=${correlationId}]")

            return [
                permitted: permitted,
                restrictions: restrictions,
                sourceTeam: sourceTeam,
                targetBusinessUnit: targetBusinessUnit,
                targetTeam: targetTeam
            ]

        } catch (Exception e) {
            String msg = "Cross-team access validation failed: ${e.message}"
            LoggingUtils.error("TeamGovernanceManager", msg, e)
            return [permitted: false, restrictions: [[type: "VALIDATION_ERROR", message: msg]]]
        }
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private Map validateSingleTeam(String teamName) {
        List<Map<String, Object>> errors = []
        List<Map<String, Object>> warnings = []

        if (!ValidationUtils.isNonEmpty(teamName)) {
            errors.add([type: "EMPTY_TEAM_NAME", message: "Team name must not be empty"])
            return [valid: false, errors: errors, warnings: warnings]
        }

        String normalized = teamName.trim()

        if (normalized.length() > MAX_TEAM_NAME_LENGTH) {
            errors.add([
                type: "TEAM_NAME_TOO_LONG",
                name: normalized,
                length: normalized.length(),
                limit: MAX_TEAM_NAME_LENGTH,
                message: "Team name '${normalized}' exceeds maximum length of ${MAX_TEAM_NAME_LENGTH}"
            ])
        }

        if (!normalized.matches("^[a-zA-Z][a-zA-Z0-9._\\-]*$")) {
            errors.add([
                type: "INVALID_TEAM_NAME_FORMAT",
                name: normalized,
                message: "Team name '${normalized}' must start with a letter and contain only alphanumeric, dot, underscore, or hyphen characters"
            ])
        }

        for (String prefix : PROHIBITED_TEAM_NAME_PREFIXES) {
            if (normalized.toLowerCase().startsWith(prefix)) {
                errors.add([
                    type: "PROHIBITED_TEAM_NAME_PREFIX",
                    name: normalized,
                    prefix: prefix,
                    message: "Team name '${normalized}' uses prohibited prefix '${prefix}'"
                ])
            }
        }

        return [
            valid: errors.isEmpty(),
            errors: errors,
            warnings: warnings,
            teamName: normalized
        ]
    }

    @NonCPS
    private String buildNamespace(String businessUnit, String name) {
        if (!ValidationUtils.isNonEmpty(businessUnit)) businessUnit = "unknown"
        if (!ValidationUtils.isNonEmpty(name)) name = "unknown"
        return "${businessUnit.toLowerCase()}.${name.toLowerCase().replaceAll('[^a-z0-9]', '-')}"
    }

    @NonCPS
    private String extractBusinessUnitFromTeam(String teamName) {
        if (!ValidationUtils.isNonEmpty(teamName)) return ""
        if (teamName.contains(".")) {
            return teamName.substring(0, teamName.indexOf("."))
        }
        return teamName
    }
}
