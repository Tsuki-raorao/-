package com.argus.controlcenter.identity;

import com.argus.controlcenter.vo.ApiResponse;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

/** 当前用户可见项目列表；SQL 先按用户成员关系过滤，再构造权限。 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {
    private final CurrentActorProvider actors;
    private final ProjectAuthorization authorization;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ProjectController(CurrentActorProvider actors, ProjectAuthorization authorization, JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.actors = actors; this.authorization = authorization; this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
    }
    @GetMapping
    public ApiResponse<List<ProjectView>> list() {
        CurrentActor actor = actors.requireCurrent();
        List<String> ids = authorization.visibleProjectIds(actor);
        if (ids.isEmpty()) return ApiResponse.ok(List.of());
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<ProjectView> rows = jdbc.query("SELECT p.id,p.name,p.status,pm.role,p.permission_version FROM projects p "
                        + "JOIN project_members pm ON pm.project_id=p.id AND pm.user_id=? WHERE p.id IN (" + placeholders + ") ORDER BY p.id",
                (rs, n) -> {
                    String role = rs.getString(4);
                    return new ProjectView(rs.getString(1), rs.getString(2), rs.getString(3), role, permissions(role, rs.getString(3)), rs.getLong(5));
                }, concat(actor.userId(), ids));
        return ApiResponse.ok(rows);
    }
    /** 项目管理员只能改名；归档和恢复由平台管理员接口处理。 */
    @PutMapping("/{projectId}")
    public ApiResponse<ProjectView> rename(@PathVariable String projectId,@RequestBody RenameProject body) {
        CurrentActor actor=actors.requireCurrent();
        if(body==null||body.name()==null||body.name().isBlank()) throw new IdentityAuthorizationException("INVALID_REQUEST",400);
        return ApiResponse.ok(tx.execute(ignored->{
            lockGate();
            AuthorizationSnapshot snapshot=authorization.requireForUpdate(actor.userId(),actor.authMode(),projectId,ProjectPermission.PROJECT_MANAGE);
            int changed=jdbc.update("UPDATE projects SET name=?,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND permission_version=?",body.name().trim(),projectId,body.expectedVersion());
            if(changed!=1) throw new IdentityAuthorizationException("VERSION_CONFLICT",409);
            return current(projectId,actor.userId());
        }));
    }
    private ProjectView current(String projectId,String userId) {
        return jdbc.queryForObject("SELECT p.id,p.name,p.status,pm.role,p.permission_version FROM projects p JOIN project_members pm ON pm.project_id=p.id AND pm.user_id=? WHERE p.id=?",
                (rs,n)->new ProjectView(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),permissions(rs.getString(4),rs.getString(3)),rs.getLong(5)),userId,projectId);
    }
    private void lockGate(){jdbc.queryForObject("SELECT id FROM identity_config_gate WHERE id=? FOR UPDATE",String.class,IdentityConstants.IDENTITY_CONFIG_GATE_ID);}
    private static Object[] concat(String userId, List<String> ids) { Object[] values = new Object[ids.size()+1]; values[0]=userId; for(int i=0;i<ids.size();i++) values[i+1]=ids.get(i); return values; }
    private static List<String> permissions(String role, String status) {
        EnumSet<ProjectPermission> set = EnumSet.of(ProjectPermission.RESOURCE_READ);
        if (ProjectRole.OPERATOR.name().equals(role)) set.add(ProjectPermission.TASK_OPERATE);
        if (ProjectRole.ADMIN.name().equals(role)) set.addAll(EnumSet.of(ProjectPermission.TASK_OPERATE,ProjectPermission.TASK_REVIEW,ProjectPermission.PROJECT_MANAGE,ProjectPermission.MEMBER_MANAGE,ProjectPermission.AUDIT_READ));
        if (ProjectStatus.ARCHIVED.name().equals(status)) { set.remove(ProjectPermission.TASK_OPERATE); set.remove(ProjectPermission.TASK_REVIEW); }
        return set.stream().map(Enum::name).sorted().toList();
    }
    public record ProjectView(String id, String name, String status, String role, List<String> permissions, long permissionVersion) { }
    public record RenameProject(String name,long expectedVersion) { }
}
