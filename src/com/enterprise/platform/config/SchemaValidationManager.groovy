package com.enterprise.platform.config

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class SchemaValidationManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> REQUIRED_PROJECT_METADATA_FIELDS = [
        "businessUnit",
        "projectName",
        "repositoryUrl"
    ]

    private static final List<String> OPTIONAL_PROJECT_METADATA_FIELDS = [
        "team",
        "description",
        "scmCredentialsId",
        "jdkVersion",
        "mavenVersion",
        "timezone"
    ]

    private static final List<String> VALID_JDK_VERSIONS = [
        "11", "17", "21"
    ]

    private static final int MAX_PROJECT_NAME_LENGTH = 64
    private static final int MAX_BUSINESS_UNIT_LENGTH = 48
    private static final int MAX_TEAM_LENGTH = 48

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId
    private final List<String> validationErrors = []
    private final List<String> validationWarnings = []

    SchemaValidationManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    SchemaValidationManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    Boolean validate(Map config) {
        LoggingUtils.info("SchemaValidationManager",
            "Starting schema validation [correlationId=${correlationId}]")

        validationErrors.clear()
        validationWarnings.clear()

        if (config == null || config.isEmpty()) {
            addError("Configuration is null or empty. A valid project configuration must be provided.")
            telemetry.emitEvent("validation", "failed", [
                correlationId: correlationId,
                errorCount: 1,
                summary: "Configuration is null or empty"
            ])
            return false
        }

        try {
            validateProjectSection(config)
            validateBranchGovernanceSection(config)
            validateRbacSection(config)
            validateNexusSection(config)
            validateRepositoriesSection(config)
            validateQualitySection(config)
            validateSecuritySection(config)
            validateNotificationsSection(config)

            boolean passed = validationErrors.isEmpty()
            int warningCount = validationWarnings.size()

            if (passed) {
                LoggingUtils.info("SchemaValidationManager",
                    "Schema validation passed: ${validationErrors.size()} errors, ${warningCount} warnings [correlationId=${correlationId}]")
                audit.emitAuditEvent("VALIDATION_PASSED",
                    "Configuration schema validation passed (${warningCount} warnings)", correlationId)
                telemetry.emitEvent("validation", "passed", [
                    correlationId: correlationId,
                    warningCount: warningCount
                ])
            } else {
                LoggingUtils.warn("SchemaValidationManager",
                    "Schema validation failed: ${validationErrors.size()} errors, ${warningCount} warnings [correlationId=${correlationId}]")
                audit.emitAuditEvent("VALIDATION_FAILED",
                    "Configuration schema validation failed: ${validationErrors.join('; ')}", correlationId)
                telemetry.emitEvent("validation", "failed", [
                    correlationId: correlationId,
                    errorCount: validationErrors.size(),
                    errors: validationErrors,
                    warningCount: warningCount
                ])
            }

            return passed

        } catch (Exception e) {
            addError("Unexpected validation failure: ${e.message}")
            LoggingUtils.error("SchemaValidationManager",
                "Schema validation encountered unexpected error: ${e.message}", e)
            telemetry.emitEvent("validation", "error", [
                correlationId: correlationId,
                error: e.message,
                errorClass: e.getClass().name
            ])
            return false
        }
    }

    List<String> getValidationErrors() {
        return new ArrayList<>(validationErrors)
    }

    List<String> getValidationWarnings() {
        return new ArrayList<>(validationWarnings)
    }

    Boolean hasErrors() {
        return !validationErrors.isEmpty()
    }

    Boolean hasWarnings() {
        return !validationWarnings.isEmpty()
    }

    /*
     * Section validators
     */

    private void validateProjectSection(Map config) {
        Map project = extractMap(config, "project")
        if (project == null || project.isEmpty()) {
            addError("'project' section is missing or empty. The project section must define metadata and build configuration.")
            return
        }

        Map metadata = extractMap(project, "metadata")
        if (metadata == null || metadata.isEmpty()) {
            addError("'project.metadata' section is missing or empty. Metadata must include businessUnit, projectName, and repositoryUrl.")
            return
        }

        for (String requiredField : REQUIRED_PROJECT_METADATA_FIELDS) {
            String value = extractString(metadata, requiredField)
            if (!ValidationUtils.isNonEmpty(value)) {
                addError("'project.metadata.${requiredField}' is required but was not provided or is empty.")
            }
        }

        String businessUnit = extractString(metadata, "businessUnit")
        if (ValidationUtils.isNonEmpty(businessUnit)) {
            if (!businessUnit.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
                addError("'project.metadata.businessUnit' must start with a letter and contain only alphanumeric, underscore, or hyphen characters. Got: '${businessUnit}'")
            }
            if (businessUnit.length() > MAX_BUSINESS_UNIT_LENGTH) {
                addError("'project.metadata.businessUnit' exceeds maximum length of ${MAX_BUSINESS_UNIT_LENGTH}. Got ${businessUnit.length()} characters.")
            }
        }

        String projectName = extractString(metadata, "projectName")
        if (ValidationUtils.isNonEmpty(projectName)) {
            if (!projectName.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
                addError("'project.metadata.projectName' must start with a letter and contain only alphanumeric, underscore, or hyphen characters. Got: '${projectName}'")
            }
            if (projectName.length() > MAX_PROJECT_NAME_LENGTH) {
                addError("'project.metadata.projectName' exceeds maximum length of ${MAX_PROJECT_NAME_LENGTH}. Got ${projectName.length()} characters.")
            }
        }

        String repositoryUrl = extractString(metadata, "repositoryUrl")
        if (ValidationUtils.isNonEmpty(repositoryUrl)) {
            if (!repositoryUrl.matches("^(https?|ssh|git)://.*") && !repositoryUrl.matches("^git@.*")) {
                addWarning("'project.metadata.repositoryUrl' may not be a valid Git URL. Validate the format: '${repositoryUrl}'")
            }
        }

        String team = extractString(metadata, "team")
        if (ValidationUtils.isNonEmpty(team) && team.length() > MAX_TEAM_LENGTH) {
            addError("'project.metadata.team' exceeds maximum length of ${MAX_TEAM_LENGTH}. Got ${team.length()} characters.")
        }

        String jdkVersion = extractString(metadata, "jdkVersion")
        if (ValidationUtils.isNonEmpty(jdkVersion) && !VALID_JDK_VERSIONS.contains(jdkVersion)) {
            addWarning("'project.metadata.jdkVersion' value '${jdkVersion}' is not in the standard set: ${VALID_JDK_VERSIONS.join(', ')}")
        }

        List<String> unrecognizedFields = []
        for (String key : metadata.keySet()) {
            if (!REQUIRED_PROJECT_METADATA_FIELDS.contains(key) && !OPTIONAL_PROJECT_METADATA_FIELDS.contains(key)) {
                unrecognizedFields.add(key)
            }
        }
        if (!unrecognizedFields.isEmpty()) {
            addWarning("Unrecognized fields in 'project.metadata': ${unrecognizedFields.join(', ')}")
        }
    }

    private void validateBranchGovernanceSection(Map config) {
        Map branchGovernance = extractMap(config, "branchGovernance")
        if (branchGovernance == null || branchGovernance.isEmpty()) {
            addWarning("'branchGovernance' section is not defined. Default policy will include all branches.")
            return
        }

        Object includeRaw = branchGovernance.get("include")
        if (includeRaw != null) {
            List<String> includes = coerceToStringList(includeRaw)
            if (includes.isEmpty()) {
                addWarning("'branchGovernance.include' is present but contains no patterns. All branches will be included by default.")
            }
            for (String pattern : includes) {
                if (pattern == null || pattern.trim().isEmpty()) {
                    addError("'branchGovernance.include' contains an empty pattern.")
                } else {
                    try {
                        java.util.regex.Pattern.compile(pattern)
                    } catch (java.util.regex.PatternSyntaxException e) {
                        addError("'branchGovernance.include' contains invalid regex pattern '${pattern}': ${e.message}")
                    }
                    if (pattern.contains(".*") && pattern.startsWith(".*")) {
                        addWarning("'branchGovernance.include' pattern '${pattern}' starts with a wildcard and may match unintended branches.")
                    }
                }
            }
        }

        Object excludeRaw = branchGovernance.get("exclude")
        if (excludeRaw != null) {
            List<String> excludes = coerceToStringList(excludeRaw)
            for (String pattern : excludes) {
                if (pattern == null || pattern.trim().isEmpty()) {
                    addError("'branchGovernance.exclude' contains an empty pattern.")
                } else {
                    try {
                        java.util.regex.Pattern.compile(pattern)
                    } catch (java.util.regex.PatternSyntaxException e) {
                        addError("'branchGovernance.exclude' contains invalid regex pattern '${pattern}': ${e.message}")
                    }
                }
            }
        }

        List<String> knownKeys = ["include", "exclude"]
        for (String key : branchGovernance.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'branchGovernance.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateRbacSection(Map config) {
        Map rbac = extractMap(config, "rbac")
        if (rbac == null || rbac.isEmpty()) {
            addWarning("'rbac' section is not defined. No permissions will be assigned to the project folder.")
            return
        }

        Object adminsRaw = rbac.get("admins")
        if (adminsRaw != null) {
            List<String> admins = coerceToStringList(adminsRaw)
            if (admins.isEmpty()) {
                addWarning("'rbac.admins' is present but contains no entries. No admin permissions will be assigned.")
            }
            for (String admin : admins) {
                if (!ValidationUtils.isNonEmpty(admin)) {
                    addError("'rbac.admins' contains an empty or null entry.")
                }
            }
        } else {
            addWarning("'rbac.admins' is not defined. No admin permissions will be assigned.")
        }

        Object developersRaw = rbac.get("developers")
        if (developersRaw != null) {
            List<String> developers = coerceToStringList(developersRaw)
            if (developers.isEmpty()) {
                addWarning("'rbac.developers' is present but contains no entries. No developer permissions will be assigned.")
            }
            for (String developer : developers) {
                if (!ValidationUtils.isNonEmpty(developer)) {
                    addError("'rbac.developers' contains an empty or null entry.")
                }
            }
        } else {
            addWarning("'rbac.developers' is not defined. No developer permissions will be assigned.")
        }

        List<String> knownKeys = ["admins", "developers"]
        for (String key : rbac.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'rbac.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateNexusSection(Map config) {
        Map nexus = extractMap(config, "nexus")
        if (nexus == null || nexus.isEmpty()) {
            addWarning("'nexus' section is not defined. Nexus repository provisioning will be skipped.")
            return
        }

        String blobStore = extractString(nexus, "blobStore")
        if (ValidationUtils.isNonEmpty(blobStore)) {
            if (!blobStore.matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
                addError("'nexus.blobStore' must start with a letter and contain only alphanumeric, underscore, or hyphen characters. Got: '${blobStore}'")
            }
            if (blobStore.length() > 64) {
                addError("'nexus.blobStore' exceeds maximum length of 64 characters.")
            }
        }

        Object reposRaw = nexus.get("repositories")
        if (reposRaw instanceof List) {
            List repos = (List) reposRaw
            if (repos.isEmpty()) {
                addWarning("'nexus.repositories' is an empty list. No repositories will be provisioned.")
            }
            for (int i = 0; i < repos.size(); i++) {
                Object repoObj = repos.get(i)
                if (!(repoObj instanceof Map)) {
                    addError("Entry ${i} in 'nexus.repositories' must be a mapping structure, got ${repoObj.getClass().simpleName}")
                } else {
                    Map repo = (Map) repoObj
                    String name = extractString(repo, "name")
                    String format = extractString(repo, "format")
                    String type = extractString(repo, "type")
                    if (!ValidationUtils.isNonEmpty(name)) {
                        addError("Entry ${i} in 'nexus.repositories' is missing required 'name' field.")
                    }
                    if (!ValidationUtils.isNonEmpty(format)) {
                        addError("Entry ${i} in 'nexus.repositories' is missing required 'format' field.")
                    } else if (!["maven2", "npm", "pypi", "docker", "raw"].contains(format)) {
                        addWarning("Entry '${name}' in 'nexus.repositories' has format '${format}' which may not be standard.")
                    }
                    if (!ValidationUtils.isNonEmpty(type)) {
                        addError("Entry ${i} in 'nexus.repositories' is missing required 'type' field.")
                    } else if (!["hosted", "proxy", "group"].contains(type)) {
                        addError("Entry '${name}' in 'nexus.repositories' has invalid type '${type}'. Must be one of: hosted, proxy, group.")
                    }
                }
            }
        } else if (reposRaw != null) {
            addError("'nexus.repositories' must be a list of repository definitions.")
        }

        List<String> knownKeys = ["blobStore", "repositories", "cleanupPolicies", "proxy", "url", "credentialsId"]
        for (String key : nexus.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'nexus.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateRepositoriesSection(Map config) {
        Map repositories = extractMap(config, "repositories")
        if (repositories == null || repositories.isEmpty()) {
            addWarning("'repositories' section is not defined. No additional repository references configured.")
            return
        }

        Object scmRaw = repositories.get("scm")
        if (scmRaw != null && !(scmRaw instanceof Map)) {
            addError("'repositories.scm' must be a mapping structure.")
        }

        List<String> knownKeys = ["scm", "docker", "artifactories", "additional"]
        for (String key : repositories.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'repositories.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateQualitySection(Map config) {
        Map quality = extractMap(config, "quality")
        if (quality == null || quality.isEmpty()) {
            addWarning("'quality' section is not defined. Quality engineering gates will be bypassed.")
            return
        }

        Object sonarQubeRaw = quality.get("sonarQube")
        if (sonarQubeRaw != null && !(sonarQubeRaw instanceof Map)) {
            addError("'quality.sonarQube' must be a mapping structure.")
        }

        Object coverageRaw = quality.get("coverage")
        if (coverageRaw != null) {
            if (coverageRaw instanceof Map) {
                Map coverage = (Map) coverageRaw
                Object thresholdRaw = coverage.get("threshold")
                if (thresholdRaw != null) {
                    if (thresholdRaw instanceof Number) {
                        Number threshold = (Number) thresholdRaw
                        if (threshold.doubleValue() < 0 || threshold.doubleValue() > 100) {
                            addError("'quality.coverage.threshold' must be between 0 and 100. Got: ${threshold}")
                        }
                    } else {
                        addError("'quality.coverage.threshold' must be a numeric value.")
                    }
                }
            } else {
                addError("'quality.coverage' must be a mapping structure.")
            }
        }

        List<String> knownKeys = ["sonarQube", "coverage", "qualityGate", "enabled"]
        for (String key : quality.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'quality.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateSecuritySection(Map config) {
        Map security = extractMap(config, "security")
        if (security == null || security.isEmpty()) {
            addWarning("'security' section is not defined. Security scanning gates will use default policies.")
            return
        }

        Object trivyRaw = security.get("trivy")
        if (trivyRaw != null && !(trivyRaw instanceof Map)) {
            addError("'security.trivy' must be a mapping structure.")
        }

        Object secretScanRaw = security.get("secretScan")
        if (secretScanRaw != null && !(secretScanRaw instanceof Map)) {
            addError("'security.secretScan' must be a mapping structure.")
        }

        Object complianceRaw = security.get("compliance")
        if (complianceRaw != null && !(complianceRaw instanceof Map)) {
            addError("'security.compliance' must be a mapping structure.")
        }

        Object sbomRaw = security.get("sbom")
        if (sbomRaw != null && !(sbomRaw instanceof Map)) {
            addError("'security.sbom' must be a mapping structure.")
        }

        Object signingRaw = security.get("signing")
        if (signingRaw != null && !(signingRaw instanceof Map)) {
            addError("'security.signing' must be a mapping structure.")
        }

        List<String> knownKeys = ["trivy", "dependencyScan", "secretScan", "compliance", "sbom", "signing", "enabled"]
        for (String key : security.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'security.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    private void validateNotificationsSection(Map config) {
        Map notifications = extractMap(config, "notifications")
        if (notifications == null || notifications.isEmpty()) {
            return
        }

        Object emailRaw = notifications.get("email")
        if (emailRaw != null && !(emailRaw instanceof Map)) {
            addError("'notifications.email' must be a mapping structure.")
        }

        Object slackRaw = notifications.get("slack")
        if (slackRaw != null && !(slackRaw instanceof Map)) {
            addError("'notifications.slack' must be a mapping structure.")
        }

        Object teamsRaw = notifications.get("teams")
        if (teamsRaw != null && !(teamsRaw instanceof Map)) {
            addError("'notifications.teams' must be a mapping structure.")
        }

        List<String> knownKeys = ["email", "slack", "teams", "webhook"]
        for (String key : notifications.keySet()) {
            if (!knownKeys.contains(key)) {
                addWarning("Unrecognized field 'notifications.${key}'. Expected fields: ${knownKeys.join(', ')}")
            }
        }
    }

    /*
     * Helper methods
     */

    @NonCPS
    private void addError(String message) {
        validationErrors.add(message)
    }

    @NonCPS
    private void addWarning(String message) {
        validationWarnings.add(message)
    }

    @NonCPS
    private Map extractMap(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof Map) return (Map) value
        return null
    }

    @NonCPS
    private String extractString(Map parent, String key) {
        if (parent == null) return null
        Object value = parent.get(key)
        if (value instanceof String) return (String) value
        if (value != null) return value.toString()
        return null
    }

    @NonCPS
    private List<String> coerceToStringList(Object raw) {
        List<String> result = []
        if (raw == null) return result
        if (raw instanceof List) {
            for (Object item : (List) raw) {
                if (item != null) {
                    result.add(item.toString())
                }
            }
        } else {
            result.add(raw.toString())
        }
        return result
    }

    @NonCPS
    String getCorrelationId() {
        return this.correlationId
    }
}
