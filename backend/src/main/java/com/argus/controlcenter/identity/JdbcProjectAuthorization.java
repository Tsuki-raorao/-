package com.argus.controlcenter.identity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 项目授权唯一实现；查询和写入都不信任客户端的项目、用户或角色字段。 */
@Service
public class JdbcProjectAuthorization implements ProjectAuthorization {
    private final JdbcTemplate jdbc;
    private final IdentityModeProperties properties;

    public JdbcProjectAuthorization(JdbcTemplate jdbc, IdentityModeProperties properties) {
        this.jdbc = jdbc; this.properties = properties;
    }

    @Override
    public AuthorizationSnapshot require(CurrentActor actor, String projectId, ProjectPermission permission) {
        if (actor == null || projectId == null || projectId.isBlank() || permission == null)
            throw denied();
        return read(actor, projectId, permission, false);
    }

    @Override
    public AuthorizationSnapshot requireForUpdate(String userId, AuthMode authMode, String projectId,
                                                  ProjectPermission permission) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IdentityAuthorizationException("AUTHORIZATION_TRANSACTION_REQUIRED", 500);
        if (userId == null || authMode == null || projectId == null || permission == null) throw denied();
        CurrentActor actor = actorForUpdate(userId, authMode);
        return read(actor, projectId, permission, true);
    }

    @Override
    public AuthorizationSnapshot requirePlatformAdminForUpdate(CurrentActor actor, String projectId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IdentityAuthorizationException("AUTHORIZATION_TRANSACTION_REQUIRED", 500);
        if (!IdentityConstants.IDENTITY_CONFIG_GATE_ID.equals(lockGate())) throw denied();
        // The project row is locked before role is re-read, so revocation and this write serialize.
        readProjectForUpdate(projectId);
        requirePlatformAdmin(actor);
        return new AuthorizationSnapshot(actor.userId(), projectId, permissionVersion(projectId),
                EnumSet.allOf(ProjectPermission.class));
    }

    @Override
    public void requirePlatformAdmin(CurrentActor actor) {
        if (actor == null) throw denied();
        if (actor.legacyOperator() && properties.getMode() == IdentityModeProperties.Mode.LEGACY_TOKEN) return;
        if (actor.platformRole() != PlatformRole.ADMIN) throw denied();
        try {
            String status = jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, actor.userId());
            if (!IdentityStatus.ACTIVE.name().equals(status)) throw denied();
        } catch (EmptyResultDataAccessException e) { throw denied(); }
    }

    @Override
    public List<String> visibleProjectIds(CurrentActor actor) {
        if (actor == null) return List.of();
        if (actor.legacyOperator() && properties.getMode() == IdentityModeProperties.Mode.LEGACY_TOKEN)
            return List.of(IdentityConstants.LEGACY_PROJECT_ID);
        return jdbc.query("SELECT pm.project_id FROM project_members pm JOIN projects p ON p.id=pm.project_id "
                        + "WHERE pm.user_id=? AND p.status IN ('ACTIVE','ARCHIVED') ORDER BY pm.project_id",
                (rs, row) -> rs.getString(1), actor.userId());
    }

    @Override
    public boolean isIdentityMode() { return properties.getMode() == IdentityModeProperties.Mode.OIDC_IDENTITY; }

    private AuthorizationSnapshot read(CurrentActor actor, String projectId, ProjectPermission permission, boolean lock) {
        ProjectRow project = lock ? readProjectForUpdate(projectId) : readProject(projectId);
        if (project == null) throw denied();
        if (actor.legacyOperator() && properties.getMode() == IdentityModeProperties.Mode.LEGACY_TOKEN
                && !IdentityConstants.LEGACY_PROJECT_ID.equals(projectId)) throw denied();
        RoleRow role;
        try {
            role = jdbc.queryForObject("SELECT pm.role,u.status FROM project_members pm JOIN users u ON u.id=pm.user_id "
                            + "WHERE pm.project_id=? AND pm.user_id=?", (rs, n) -> new RoleRow(rs.getString(1), rs.getString(2)),
                    projectId, actor.userId());
        } catch (EmptyResultDataAccessException e) { throw denied(); }
        if (!IdentityStatus.ACTIVE.name().equals(role.status())) throw denied();
        Set<ProjectPermission> permissions = permissions(role.role(), project.status());
        if (!permissions.contains(permission)) throw denied();
        return new AuthorizationSnapshot(actor.userId(), projectId, project.version(), permissions);
    }

    private CurrentActor actorForUpdate(String userId, AuthMode authMode) {
        try {
            String[] row = jdbc.queryForObject("SELECT platform_role,status FROM users WHERE id=?", (rs, n) ->
                    new String[]{rs.getString(1), rs.getString(2)}, userId);
            if (!IdentityStatus.ACTIVE.name().equals(row[1])) throw denied();
            return new CurrentActor(userId, authMode, PlatformRole.valueOf(row[0]),
                    authMode == AuthMode.LEGACY_TOKEN && IdentityConstants.LEGACY_USER_ID.equals(userId));
        } catch (EmptyResultDataAccessException | IllegalArgumentException e) { throw denied(); }
    }

    private ProjectRow readProject(String id) {
        try { return jdbc.queryForObject("SELECT id,status,permission_version FROM projects WHERE id=?",
                (rs, n) -> new ProjectRow(rs.getString(1), rs.getString(2), rs.getLong(3)), id); }
        catch (EmptyResultDataAccessException e) { return null; }
    }
    private ProjectRow readProjectForUpdate(String id) {
        try { return jdbc.queryForObject("SELECT id,status,permission_version FROM projects WHERE id=? FOR UPDATE",
                (rs, n) -> new ProjectRow(rs.getString(1), rs.getString(2), rs.getLong(3)), id); }
        catch (EmptyResultDataAccessException e) { throw denied(); }
    }
    private long permissionVersion(String projectId) { return readProjectForUpdate(projectId).version(); }
    private String lockGate() {
        try { return jdbc.queryForObject("SELECT id FROM identity_config_gate WHERE id=? FOR UPDATE", String.class,
                IdentityConstants.IDENTITY_CONFIG_GATE_ID); }
        catch (EmptyResultDataAccessException e) { throw new IdentityAuthorizationException("IDENTITY_NOT_MIGRATED", 500); }
    }
    private static Set<ProjectPermission> permissions(String role, String status) {
        ProjectRole parsed;
        try { parsed = ProjectRole.valueOf(role); } catch (IllegalArgumentException e) { return Set.of(); }
        EnumSet<ProjectPermission> set = EnumSet.of(ProjectPermission.RESOURCE_READ);
        if (parsed == ProjectRole.OPERATOR) set.add(ProjectPermission.TASK_OPERATE);
        if (parsed == ProjectRole.ADMIN) set.addAll(EnumSet.of(ProjectPermission.TASK_OPERATE, ProjectPermission.TASK_REVIEW,
                ProjectPermission.PROJECT_MANAGE, ProjectPermission.MEMBER_MANAGE, ProjectPermission.AUDIT_READ));
        if (ProjectStatus.ARCHIVED.name().equals(status)) { set.remove(ProjectPermission.TASK_OPERATE); set.remove(ProjectPermission.TASK_REVIEW); }
        return Set.copyOf(set);
    }
    private static IdentityAuthorizationException denied() { return new IdentityAuthorizationException("PERMISSION_DENIED", 403); }
    private record ProjectRow(String id, String status, long version) { }
    private record RoleRow(String role, String status) { }
}
