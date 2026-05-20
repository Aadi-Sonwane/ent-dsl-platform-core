package com.enterprise.platform.rbac

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class FolderAuthorizationManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final String INHERITANCE_STRATEGY = "noInheritance"

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final EnterpriseRBACManager rbacManager
    private final String correlationId

    FolderAuthorizationManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.rbacManager = new EnterpriseRBACManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    FolderAuthorizationManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.rbacManager = new EnterpriseRBACManager(steps, correlationId)
        this.correlationId = correlationId
    }

    Map applyFolderAuthorization(String folderPath, Map rbacConfig) {
        LoggingUtils.info("FolderAuthorizationManager",
            "Applying folder authorization for '${folderPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(folderPath)) {
            throw new IllegalArgumentException("Folder path must not be null or empty")
        }
        if (rbacConfig == null) rbacConfig = [:]

        try {
            List<String> permissionEntries = rbacManager.buildAuthorizationMatrixEntries(rbacConfig)
            Map validationResult = rbacManager.validateRbacConfiguration(rbacConfig)

            if (!validationResult.valid instanceof Boolean || !((Boolean) validationResult.valid)) {
                List errors = validationResult.errors instanceof List ? (List) validationResult.errors : []
                String errMsg = "RBAC configuration validation failed: ${errors.collect { it.message?.toString() ?: 'unknown error' }.join('; ')}"
                LoggingUtils.error("FolderAuthorizationManager", errMsg, null)
                audit.emitAuditEvent("FOLDER_AUTH_FAILED", errMsg, correlationId)
                throw new IllegalArgumentException(errMsg)
            }

            String inheritanceConfig = buildInheritanceStrategyConfig()

            Map result = [
                status: "APPLIED",
                folderPath: folderPath,
                permissionCount: permissionEntries.size(),
                inheritanceStrategy: INHERITANCE_STRATEGY,
                permissions: permissionEntries,
                admins: resolveRoleCount(rbacConfig, "admins"),
                developers: resolveRoleCount(rbacConfig, "developers"),
                viewers: resolveRoleCount(rbacConfig, "viewers"),
                releaseManagers: resolveRoleCount(rbacConfig, "releaseManagers"),
                warnings: validationResult.warnings instanceof List ?
                    (List<Map>) validationResult.warnings : []
            ]

            LoggingUtils.info("FolderAuthorizationManager",
                "Folder authorization applied to '${folderPath}': ${permissionEntries.size()} permissions, inheritance disabled [correlationId=${correlationId}]")

            audit.emitAuditEvent("FOLDER_AUTHORIZATION_APPLIED",
                "Authorization applied to folder '${folderPath}': ${permissionEntries.size()} permissions", correlationId)
            telemetry.emitEvent("rbac.folder", "authorization_applied", [
                correlationId: correlationId,
                folderPath: folderPath,
                permissionCount: permissionEntries.size(),
                inheritanceDisabled: true
            ])

            return result

        } catch (IllegalArgumentException e) {
            throw e
        } catch (Exception e) {
            String errMsg = "Failed to apply folder authorization for '${folderPath}': ${e.message}"
            LoggingUtils.error("FolderAuthorizationManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map validateFolderSecurity(String folderPath, Map rbacConfig) {
        LoggingUtils.info("FolderAuthorizationManager",
            "Validating folder security for '${folderPath}' [correlationId=${correlationId}]")

        if (!ValidationUtils.isNonEmpty(folderPath)) {
            return [valid: false, errors: ["Folder path is required"], warnings: []]
        }

        List<Map<String, Object>> errors = []
        List<Map<String, Object>> warnings = []

        if (rbacConfig == null || rbacConfig.isEmpty()) {
            warnings.add([
                type: "NO_RBAC_CONFIG",
                message: "No RBAC configuration provided for folder '${folderPath}'. Default permissions will be empty."
            ])
        } else {
            List<String> admins = resolveRoleMembers(rbacConfig, "admins")
            List<String> developers = resolveRoleMembers(rbacConfig, "developers")

            if (!admins.isEmpty()) {
                boolean hasAdminPermissions = false
                for (String admin : admins) {
                    if (ValidationUtils.isNonEmpty(admin)) {
                        hasAdminPermissions = true
                        break
                    }
                }
                if (!hasAdminPermissions) {
                    warnings.add([
                        type: "EMPTY_ADMIN_ENTRIES",
                        message: "Admin role is defined but contains no valid entries."
                    ])
                }
            }

            if (developers.isEmpty()) {
                warnings.add([
                    type: "NO_DEVELOPERS",
                    message: "No developers defined for folder '${folderPath}'. Only admins will have access."
                ])
            }
        }

        return [
            valid: errors.isEmpty(),
            errors: errors,
            warnings: warnings,
            folderPath: folderPath
        ]
    }

    String getAppliedAuthorizationSummary(String folderPath, Map rbacConfig) {
        if (!ValidationUtils.isNonEmpty(folderPath)) return ""
        if (rbacConfig == null) return ""

        List<String> admins = resolveRoleMembers(rbacConfig, "admins")
        List<String> developers = resolveRoleMembers(rbacConfig, "developers")

        return "Folder '${folderPath}': ${admins.size()} admin(s), ${developers.size()} developer(s), inheritance disabled"
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private String buildInheritanceStrategyConfig() {
        return """
                    inheritanceStrategies {
                                        noInheritance()
                    }
        """.trim()
    }

    @NonCPS
    private int resolveRoleCount(Map rbacConfig, String roleKey) {
        if (rbacConfig == null) return 0
        Object raw = rbacConfig.get(roleKey)
        if (raw instanceof List) {
            Set<String> unique = new LinkedHashSet<>()
            for (Object item : (List) raw) {
                if (item instanceof String && ValidationUtils.isNonEmpty((String) item)) {
                    unique.add(((String) item).trim())
                }
            }
            return unique.size()
        }
        return 0
    }

    @NonCPS
    private List<String> resolveRoleMembers(Map rbacConfig, String roleKey) {
        List<String> members = []
        if (rbacConfig == null) return members
        Object raw = rbacConfig.get(roleKey)
        if (raw instanceof List) {
            for (Object item : (List) raw) {
                if (item instanceof String) members.add(((String) item).trim())
            }
        }
        return members
    }
}
