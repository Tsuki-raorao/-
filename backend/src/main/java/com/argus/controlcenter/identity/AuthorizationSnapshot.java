package com.argus.controlcenter.identity;

import java.util.Set;

public record AuthorizationSnapshot(String userId, String projectId, long permissionVersion,
                                    Set<ProjectPermission> permissions) {
    public AuthorizationSnapshot {
        permissions = Set.copyOf(permissions == null ? Set.of() : permissions);
    }
    public boolean allows(ProjectPermission permission) { return permissions.contains(permission); }
}
