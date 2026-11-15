package com.portfolio.deployops.dto;

import com.portfolio.deployops.domain.UserRole;
import java.util.List;

public record ProjectPermissionView(
    Long projectId, String projectName, UserSummary user, String revision,
    boolean editable, boolean canGrant, String editRestriction,
    List<RawPermission> rawPermissions, List<EffectivePermission> effectivePermissions,
    List<ProjectPermissionForm.Grant> availableGrants, List<String> roleNotes
) {
    public record UserSummary(Long id, String loginId, String name, UserRole role, String roleLabel, boolean active) { }
    public record RawPermission(ProjectPermissionForm.Grant grant, boolean active) { }
    public record EffectivePermission(ProjectPermissionForm.Grant grant, String source) { }
    public record ProjectUsers(Long projectId, String projectName, List<UserSummary> users) { }
}
