package com.enterprise.platform.rbac

import com.enterprise.platform.observability.AuditLoggingManager
import com.enterprise.platform.observability.TelemetryManager
import com.enterprise.platform.utils.LoggingUtils
import com.enterprise.platform.utils.ValidationUtils

class EnterpriseRBACManager implements Serializable {
    private static final long serialVersionUID = 1L

    private static final List<String> ADMIN_PERMISSIONS = [
        "hudson.model.Item.Admin",
        "hudson.model.Item.Configure",
        "hudson.model.Item.Read",
        "hudson.model.Item.Build",
        "hudson.model.Item.Cancel",
        "hudson.model.Item.Delete",
        "hudson.model.Item.Move",
        "hudson.model.Item.Workspace",
        "hudson.model.Run.Delete",
        "hudson.model.Run.Update",
        "hudson.scm.SCM.Tag",
        "com.cloudbees.plugins.credentials.CredentialsProvider.View",
        "com.cloudbees.plugins.credentials.CredentialsProvider.Create",
        "com.cloudbees.plugins.credentials.CredentialsProvider.Update",
        "com.cloudbees.plugins.credentials.CredentialsProvider.Delete",
        "com.cloudbees.plugins.credentials.CredentialsProvider.ManageDomains"
    ]

    private static final List<String> DEVELOPER_PERMISSIONS = [
        "hudson.model.Item.Read",
        "hudson.model.Item.Build",
        "hudson.model.Item.Cancel",
        "hudson.model.Item.Workspace",
        "hudson.scm.SCM.Tag"
    ]

    private static final List<String> VIEWER_PERMISSIONS = [
        "hudson.model.Item.Read",
        "hudson.model.Item.ViewStatus"
    ]

    private static final List<String> RELEASE_MANAGER_EXTRA = [
        "hudson.model.Item.Configure",
        "hudson.model.Item.Delete",
        "hudson.model.Run.Update",
        "com.cloudbees.plugins.credentials.CredentialsProvider.View"
    ]

    private final Object steps
    private final AuditLoggingManager audit
    private final TelemetryManager telemetry
    private final String correlationId

    EnterpriseRBACManager(Object steps) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = telemetry.generateCorrelationId()
    }

    EnterpriseRBACManager(Object steps, String correlationId) {
        this.steps = steps
        this.audit = new AuditLoggingManager(steps)
        this.telemetry = new TelemetryManager(steps)
        this.correlationId = correlationId
    }

    List<String> buildAuthorizationMatrixEntries(Map rbacConfig) {
        LoggingUtils.info("EnterpriseRBACManager",
            "Building authorization matrix entries [correlationId=${correlationId}]")

        if (rbacConfig == null) {
            rbacConfig = [:]
        }

        try {
            Set<String> permissions = new LinkedHashSet<>()

            List<String> admins = resolveRoleMembers(rbacConfig, "admins")
            List<String> developers = resolveRoleMembers(rbacConfig, "developers")
            List<String> viewers = resolveRoleMembers(rbacConfig, "viewers")
            List<String> releaseManagers = resolveRoleMembers(rbacConfig, "releaseManagers")

            for (String admin : admins) {
                if (!ValidationUtils.isNonEmpty(admin)) continue
                for (String perm : ADMIN_PERMISSIONS) {
                    permissions.add("${perm}:${admin}")
                }
            }

            for (String developer : developers) {
                if (!ValidationUtils.isNonEmpty(developer)) continue
                for (String perm : DEVELOPER_PERMISSIONS) {
                    permissions.add("${perm}:${developer}")
                }
            }

            for (String viewer : viewers) {
                if (!ValidationUtils.isNonEmpty(viewer)) continue
                for (String perm : VIEWER_PERMISSIONS) {
                    permissions.add("${perm}:${viewer}")
                }
            }

            for (String rm : releaseManagers) {
                if (!ValidationUtils.isNonEmpty(rm)) continue
                for (String perm : DEVELOPER_PERMISSIONS) {
                    permissions.add("${perm}:${rm}")
                }
                for (String perm : RELEASE_MANAGER_EXTRA) {
                    permissions.add("${perm}:${rm}")
                }
            }

            List<String> permissionList = new ArrayList<>(permissions)

            LoggingUtils.info("EnterpriseRBACManager",
                "Authorization matrix built: ${permissionList.size()} entries (${admins.size()} admins, ${developers.size()} developers, ${viewers.size()} viewers, ${releaseManagers.size()} release managers) [correlationId=${correlationId}]")

            audit.emitAuditEvent("RBAC_MATRIX_BUILT",
                "Authorization matrix: ${permissionList.size()} entries", correlationId)

            return permissionList

        } catch (Exception e) {
            String errMsg = "Failed to build authorization matrix: ${e.message}"
            LoggingUtils.error("EnterpriseRBACManager", errMsg, e)
            throw new RuntimeException(errMsg, e)
        }
    }

    Map validateRbacConfiguration(Map rbacConfig) {
        LoggingUtils.info("EnterpriseRBACManager",
            "Validating RBAC configuration [correlationId=${correlationId}]")

        if (rbacConfig == null) rbacConfig = [:]

        List<Map<String, Object>> warnings = []
        List<Map<String, Object>> errors = []

        List<String> admins = resolveRoleMembers(rbacConfig, "admins")
        List<String> developers = resolveRoleMembers(rbacConfig, "developers")

        if (admins.isEmpty() && developers.isEmpty()) {
            warnings.add([
                type: "NO_RBAC_ROLES",
                message: "No admin or developer roles defined. All users will have no permissions."
            ])
        }

        if (admins.isEmpty()) {
            warnings.add([
                type: "NO_ADMINS",
                message: "No admin users or groups defined. Administrative operations may be unavailable."
            ])
        }

        for (String entry : new LinkedHashSet<>(admins)) {
            if (developers.contains(entry)) {
                warnings.add([
                    type: "ROLE_OVERLAP",
                    user: entry,
                    message: "User/group '${entry}' is listed in both admins and developers. Admin permissions will take precedence."
                ])
            }
        }

        for (String user : new LinkedHashSet<>(admins)) {
            if (!isValidRbacEntry(user)) {
                errors.add([
                    type: "INVALID_RBAC_ENTRY",
                    user: user,
                    message: "Invalid RBAC entry '${user}' in admins. Must be a valid username or group name."
                ])
            }
        }
        for (String user : new LinkedHashSet<>(developers)) {
            if (!isValidRbacEntry(user)) {
                errors.add([
                    type: "INVALID_RBAC_ENTRY",
                    user: user,
                    message: "Invalid RBAC entry '${user}' in developers. Must be a valid username or group name."
                ])
            }
        }

        return [
            valid: errors.isEmpty(),
            errors: errors,
            warnings: warnings,
            totalAdmins: new LinkedHashSet<>(admins).size(),
            totalDevelopers: new LinkedHashSet<>(developers).size()
        ]
    }

    String getCorrelationId() {
        return this.correlationId
    }

    /*
     * Private helpers
     */

    @NonCPS
    private List<String> resolveRoleMembers(Map rbacConfig, String roleKey) {
        List<String> members = []
        if (rbacConfig == null) return members
        Object raw = rbacConfig.get(roleKey)
        if (raw instanceof List) {
            for (Object item : (List) raw) {
                if (item instanceof String && ValidationUtils.isNonEmpty((String) item)) {
                    members.add(((String) item).trim())
                } else if (item != null) {
                    String s = item.toString().trim()
                    if (ValidationUtils.isNonEmpty(s)) members.add(s)
                }
            }
        } else if (raw instanceof String && ValidationUtils.isNonEmpty((String) raw)) {
            String[] parts = ((String) raw).split(",")
            for (String part : parts) {
                String trimmed = part.trim()
                if (ValidationUtils.isNonEmpty(trimmed)) members.add(trimmed)
            }
        }
        return members
    }

    @NonCPS
    private boolean isValidRbacEntry(String entry) {
        if (!ValidationUtils.isNonEmpty(entry)) return false
        return entry.matches("^[a-zA-Z][a-zA-Z0-9._@\\- ]*$")
    }

    @NonCPS
    List<String> getAdminPermissions() {
        return new ArrayList<>(ADMIN_PERMISSIONS)
    }

    @NonCPS
    List<String> getDeveloperPermissions() {
        return new ArrayList<>(DEVELOPER_PERMISSIONS)
    }

    @NonCPS
    List<String> getViewerPermissions() {
        return new ArrayList<>(VIEWER_PERMISSIONS)
    }
}
