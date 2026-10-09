package com.argus.controlcenter.identity;

import java.util.List;

public interface ProjectAuthorization {
    AuthorizationSnapshot require(CurrentActor actor, String projectId, ProjectPermission permission);
    AuthorizationSnapshot requireForUpdate(String userId, AuthMode authMode, String projectId,
                                           ProjectPermission permission);
    AuthorizationSnapshot requirePlatformAdminForUpdate(CurrentActor actor, String projectId);
    void requirePlatformAdmin(CurrentActor actor);
    List<String> visibleProjectIds(CurrentActor actor);
    boolean isIdentityMode();
}
