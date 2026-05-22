import com.enterprise.platform.config.EnterpriseYAMLParser
import com.enterprise.platform.config.SchemaValidationManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.utils.ValidationUtils
import com.enterprise.platform.utils.LoggingUtils

def call(String yamlText) {
    LoggingUtils.info("provisionBuildOSPlatform", "Starting Control Plane provisioning")

    TelemetryManager telemetry = new TelemetryManager(steps)
    AuditLoggingManager audit = new AuditLoggingManager(steps)
    String correlationId = telemetry.generateCorrelationId()

    try {
        if (!ValidationUtils.isNonEmpty(yamlText)) {
            throw new IllegalArgumentException("yamlText must not be null or empty")
        }

        EnterpriseYAMLParser parser = new EnterpriseYAMLParser(steps)
        Map parsedConfig = parser.parse(yamlText)
        LoggingUtils.info("provisionBuildOSPlatform", "YAML parsed successfully")

        SchemaValidationManager schemaValidator = new SchemaValidationManager(steps)
        Boolean schemaValid = schemaValidator.validate(parsedConfig)
        if (!schemaValid) {
            List<String> errors = schemaValidator.getValidationErrors()
            audit.emitAuditEvent(
                "PROVISION_FAILED",
                "Schema validation failed: ${errors.join('; ')}",
                correlationId
            )
            telemetry.emitEvent("provision", "validation_failed", [
                correlationId: correlationId,
                errors: errors
            ])
            error("Schema validation failed: ${errors.join('; ')}")
        }
        LoggingUtils.info("provisionBuildOSPlatform", "Schema validation passed")

        String businessUnit = extractSafely(parsedConfig, "project.metadata.businessUnit")
        String team = extractSafely(parsedConfig, "project.metadata.team")
        String projectName = extractSafely(parsedConfig, "project.metadata.projectName")
        String repoUrl = extractSafely(parsedConfig, "project.metadata.repositoryUrl")
        String scmCredentials = extractSafely(parsedConfig, "project.metadata.scmCredentialsId",
            "git")
        Map branchGovernance = parsedConfig.branchGovernance instanceof Map ?
            parsedConfig.branchGovernance : [:]
        Map rbac = parsedConfig.rbac instanceof Map ? parsedConfig.rbac : [:]

        if (!ValidationUtils.isNonEmpty(businessUnit)) {
            throw new IllegalArgumentException("project.metadata.businessUnit is required")
        }
        if (!ValidationUtils.isNonEmpty(projectName)) {
            throw new IllegalArgumentException("project.metadata.projectName is required")
        }
        if (!ValidationUtils.isNonEmpty(repoUrl)) {
            throw new IllegalArgumentException("project.metadata.repositoryUrl is required")
        }

        LoggingUtils.info("provisionBuildOSPlatform",
            "Provisioning tenant: businessUnit=${businessUnit}, project=${projectName}, team=${team}")

        /*
         * Provision organizational folder
         */
        try {
            folder(businessUnit) {
                displayName(businessUnit)
                description("Enterprise BuildOS Platform - ${businessUnit} organization")
                properties {
                    folderCredentialsProperty {
                        domainCredentials {
                            // No inherited domain credentials at top level
                        }
                    }
                }
            }
            audit.emitAuditEvent("FOLDER_CREATED", "Organizational folder ${businessUnit} provisioned", correlationId)
            telemetry.emitEvent("provision", "folder_created", [
                correlationId: correlationId,
                businessUnit: businessUnit,
                resourceType: "organizationalFolder"
            ])
        } catch (Exception e) {
            audit.emitAuditEvent("FOLDER_FAILED",
                "Failed to provision organizational folder ${businessUnit}: ${e.message}", correlationId)
            telemetry.emitEvent("provision", "folder_failed", [
                correlationId: correlationId,
                businessUnit: businessUnit,
                error: e.message
            ])
            throw e
        }

        /*
         * Provision multi-tenant project folder under business unit
         */
        String projectFolderPath = "${businessUnit}/${projectName}"
        try {
            folder(projectFolderPath) {
                displayName(projectName)
                description("BuildOS Platform - ${projectName} project for team ${team}")
                properties {
                    authorizationMatrix {
                        inheritanceStrategies {
                                            noInheritance()
                        }
                        permissions = buildPermissionsFromRbac(rbac)
                    }
                }
            }
            audit.emitAuditEvent("PROJECT_FOLDER_CREATED",
                "Project folder ${projectFolderPath} provisioned with RBAC", correlationId)
            telemetry.emitEvent("provision", "project_folder_created", [
                correlationId: correlationId,
                projectFolderPath: projectFolderPath,
                resourceType: "projectFolder"
            ])
        } catch (Exception e) {
            audit.emitAuditEvent("PROJECT_FOLDER_FAILED",
                "Failed to provision project folder ${projectFolderPath}: ${e.message}", correlationId)
            telemetry.emitEvent("provision", "project_folder_failed", [
                correlationId: correlationId,
                projectFolderPath: projectFolderPath,
                error: e.message
            ])
            throw e
        }

        /*
         * Provision multibranch pipeline job locked to Jenkinsfile.platform
         */
        try {
            multibranchPipelineJob("${projectFolderPath}/${projectName}-pipeline") {
                displayName(projectName)
                description("BuildOS Platform pipeline for ${projectName} — managed by Enterprise Internal Developer Platform")

                branchSources {
                    github {
                        id("${projectName}-source")
                        scanCredentialsId(scmCredentials)
                        repoOwner(extractRepoOwner(repoUrl))
                        repository(extractRepoName(repoUrl))
                        buildForkPRHead(false)
                        buildForkPRMerge(false)
                        buildOriginBranchWithPR(true)
                        buildOriginBranch(true)
                        buildOriginPRMerge(false)
                    }
                }

                configure { node ->
                    def sources = node / sources / data / 'jenkins.branch.BranchSource'
                    def source = sources / source
                    source / traits {
                        'jenkins.plugins.git.traits.BranchDiscoveryTrait'()
                        'jenkins.scm.impl.trait.RegexSCMHeadFilterTrait' {
                            regex(getIncludeRegex(branchGovernance))
                        }
                        if (hasExcludePatterns(branchGovernance)) {
                            'jenkins.scm.impl.trait.RegexSCMHeadFilterTrait' {
                                regex(getExcludeRegex(branchGovernance))
                            }
                        }
                    }
                }

                factory {
                    workflowBranchProjectFactory {
                        scriptPath('Jenkinsfile.platform')
                    }
                }

                orphanedItemStrategy {
                    discardOldItems {
                        daysToKeep(30)
                        numToKeep(50)
                        abandonBuilds(false)
                    }
                }
            }
            audit.emitAuditEvent("MULTIBRANCH_JOB_CREATED",
                "Multibranch pipeline ${projectName}-pipeline provisioned in ${projectFolderPath}", correlationId)
            telemetry.emitEvent("provision", "multibranch_job_created", [
                correlationId: correlationId,
                jobPath: "${projectFolderPath}/${projectName}-pipeline",
                repositoryUrl: repoUrl,
                resourceType: "multibranchPipelineJob"
            ])
        } catch (Exception e) {
            audit.emitAuditEvent("MULTIBRANCH_JOB_FAILED",
                "Failed to provision multibranch job: ${e.message}", correlationId)
            telemetry.emitEvent("provision", "multibranch_job_failed", [
                correlationId: correlationId,
                error: e.message
            ])
            throw e
        }

        LoggingUtils.info("provisionBuildOSPlatform",
            "Control Plane provisioning completed for ${businessUnit}/${projectName}")

        telemetry.emitEvent("provision", "provisioning_completed", [
            correlationId: correlationId,
            businessUnit: businessUnit,
            projectName: projectName,
            team: team,
            duration: ""
        ])

        return [
            status: "SUCCESS",
            businessUnit: businessUnit,
            projectName: projectName,
            projectFolderPath: projectFolderPath,
            correlationId: correlationId
        ]

    } catch (Exception e) {
        LoggingUtils.error("provisionBuildOSPlatform",
            "Control Plane provisioning failed: ${e.message}", e)

        telemetry.emitEvent("provision", "provisioning_failed", [
            correlationId: correlationId,
            error: e.message
        ])

        throw e
    }
}

@NonCPS
private String extractSafely(Map config, String dottedPath, String defaultValue = null) {
    List<String> parts = dottedPath.split("\\.")
    Object current = config
    for (String part : parts) {
        if (current instanceof Map && current.containsKey(part)) {
            current = current[part]
        } else {
            return defaultValue
        }
    }
    return current instanceof String ? current : (current != null ? current.toString() : defaultValue)
}

@NonCPS
private List<String> buildPermissionsFromRbac(Map rbac) {
    Set<String> permissions = [] as Set

    List admins = rbac.admins instanceof List ? rbac.admins : []
    List developers = rbac.developers instanceof List ? rbac.developers : []

    for (String admin : admins) {
        permissions.add("hudson.model.Item.Admin:${admin}")
        permissions.add("hudson.model.Item.Configure:${admin}")
        permissions.add("hudson.model.Item.Read:${admin}")
        permissions.add("hudson.model.Item.Build:${admin}")
        permissions.add("hudson.model.Item.Cancel:${admin}")
        permissions.add("hudson.model.Run.Delete:${admin}")
        permissions.add("hudson.model.Run.Update:${admin}")
        permissions.add("com.cloudbees.plugins.credentials.CredentialsProvider.View:${admin}")
        permissions.add("com.cloudbees.plugins.credentials.CredentialsProvider.Create:${admin}")
    }
    for (String developer : developers) {
        permissions.add("hudson.model.Item.Read:${developer}")
        permissions.add("hudson.model.Item.Build:${developer}")
        permissions.add("hudson.model.Item.Cancel:${developer}")
    }
    return permissions as List<String>
}

@NonCPS
private String getIncludeRegex(Map branchGovernance) {
    List includes = branchGovernance.include instanceof List ? branchGovernance.include :
        (branchGovernance.include ? [branchGovernance.include.toString()] : [])
    if (includes.isEmpty()) {
        return ".*"
    }
    return includes.collect { it.toString() }.join("|")
}

@NonCPS
private Boolean hasExcludePatterns(Map branchGovernance) {
    List excludes = branchGovernance.exclude instanceof List ? branchGovernance.exclude :
        (branchGovernance.exclude ? [branchGovernance.exclude.toString()] : [])
    return !excludes.isEmpty()
}

@NonCPS
private String getExcludeRegex(Map branchGovernance) {
    List excludes = branchGovernance.exclude instanceof List ? branchGovernance.exclude :
        (branchGovernance.exclude ? [branchGovernance.exclude.toString()] : [])
    if (excludes.isEmpty()) {
        return ""
    }
    return excludes.collect { it.toString() }.join("|")
}

@NonCPS
private String extractRepoOwner(String repoUrl) {
    if (repoUrl == null) return ""
    String normalized = repoUrl.replaceAll(/\.git$/, "")
    if (normalized.contains("github.com")) {
        List parts = normalized.split("/")
        if (parts.size() >= 2) {
            return parts[parts.size() - 2]
        }
    }
    return ""
}

@NonCPS
private String extractRepoName(String repoUrl) {
    if (repoUrl == null) return ""
    String normalized = repoUrl.replaceAll(/\.git$/, "")
    if (normalized.contains("/")) {
        List parts = normalized.split("/")
        if (parts.size() >= 1) {
            return parts[parts.size() - 1]
        }
    }
    return normalized
}
