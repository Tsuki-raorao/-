package com.argus.controlcenter.identity;

import com.argus.controlcenter.config.SecurityProperties;
import com.argus.controlcenter.vo.ApiResponse;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** 平台用户／项目管理；所有写入在配置闸门和版本号下执行。 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc; private final CurrentActorProvider actors; private final ProjectAuthorization auth; private final TransactionTemplate tx;
    public AdminController(JdbcTemplate jdbc, CurrentActorProvider actors, ProjectAuthorization auth, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.actors=actors;this.auth=auth;this.tx=new TransactionTemplate(manager);
    }
    @GetMapping("/users") public ApiResponse<List<UserView>> users() {
        auth.requirePlatformAdmin(actors.requireCurrent());
        return ApiResponse.ok(jdbc.query("SELECT id,display_name,platform_role,status,version_no FROM users ORDER BY id",
                (r,n)->new UserView(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5))));
    }
    @GetMapping("/projects") public ApiResponse<List<ProjectAdminView>> projects() {
        auth.requirePlatformAdmin(actors.requireCurrent());
        return ApiResponse.ok(jdbc.query("SELECT id,name,status,permission_version FROM projects ORDER BY id",
                (r,n)->new ProjectAdminView(r.getString(1),r.getString(2),r.getString(3),r.getLong(4))));
    }
    @PostMapping("/projects") public ApiResponse<ProjectAdminView> create(@RequestBody CreateProject body) {
        CurrentActor actor=actors.requireCurrent(); auth.requirePlatformAdmin(actor);
        if(body==null||body.name()==null||body.name().isBlank()||body.initialAdminUserId()==null||body.initialAdminUserId().isBlank()) throw bad();
        return ApiResponse.created(tx.execute(status->{
            lockGate();
            String projectId=UUID.randomUUID().toString();
            int user=jdbc.update("UPDATE users SET version_no=version_no WHERE id=? AND status='ACTIVE'",body.initialAdminUserId());
            if(user!=1) throw new IdentityAuthorizationException("RESOURCE_NOT_FOUND",404);
            jdbc.update("INSERT INTO projects(id,name,status,permission_version,created_at,updated_at) VALUES(?,?, 'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",projectId,body.name().trim());
            jdbc.update("INSERT INTO project_members(project_id,user_id,role,version_no,created_at,updated_at) VALUES(?,?, 'ADMIN',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",projectId,body.initialAdminUserId());
            return new ProjectAdminView(projectId,body.name().trim(),"ACTIVE",0L);
        }));
    }
    @PutMapping("/users/{id}") public ApiResponse<UserView> updateUser(@PathVariable String id,@RequestBody UpdateUser body) {
        CurrentActor actor=actors.requireCurrent(); auth.requirePlatformAdmin(actor); if(body==null||body.expectedVersion()<0||!Set.of("ADMIN","USER").contains(body.platformRole())||!Set.of("ACTIVE","DISABLED").contains(body.status())) throw bad();
        return ApiResponse.ok(tx.execute(s->{ lockGate(); UserView old=user(id); if(old==null) throw notFound();
            if(!old.status().equals("DISABLED") && "DISABLED".equals(body.status()) && "ADMIN".equals(old.platformRole()) && adminCount()<=1) throw new IdentityAuthorizationException("LAST_PLATFORM_ADMIN",409);
            if("ADMIN".equals(old.platformRole()) && !"ADMIN".equals(body.platformRole()) && adminCount()<=1) throw new IdentityAuthorizationException("LAST_PLATFORM_ADMIN",409);
            int changed=jdbc.update("UPDATE users SET platform_role=?,status=?,version_no=version_no+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND version_no=?",body.platformRole(),body.status(),id,body.expectedVersion());
            if(changed!=1) throw new IdentityAuthorizationException("VERSION_CONFLICT",409); return user(id);
        }));
    }
    @PutMapping("/projects/{id}") public ApiResponse<ProjectAdminView> updateProject(@PathVariable String id,@RequestBody UpdateProject body) {
        CurrentActor actor=actors.requireCurrent(); if(body==null||body.name()==null||body.name().isBlank()||body.expectedVersion()<0||!Set.of("ACTIVE","ARCHIVED").contains(body.status())) throw bad();
        return ApiResponse.ok(tx.execute(s->{auth.requirePlatformAdminForUpdate(actor,id); int changed=jdbc.update("UPDATE projects SET name=?,status=?,permission_version=permission_version+1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND permission_version=?",body.name().trim(),body.status(),id,body.expectedVersion()); if(changed!=1) throw new IdentityAuthorizationException("VERSION_CONFLICT",409); return project(id); }));
    }
    private void lockGate(){jdbc.queryForObject("SELECT id FROM identity_config_gate WHERE id=? FOR UPDATE",String.class,IdentityConstants.IDENTITY_CONFIG_GATE_ID);}
    private long adminCount(){return jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE platform_role='ADMIN' AND status='ACTIVE'",Long.class);}
    private UserView user(String id){var rows=jdbc.query("SELECT id,display_name,platform_role,status,version_no FROM users WHERE id=?",(r,n)->new UserView(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getLong(5)),id);return rows.isEmpty()?null:rows.get(0);}
    private ProjectAdminView project(String id){var rows=jdbc.query("SELECT id,name,status,permission_version FROM projects WHERE id=?",(r,n)->new ProjectAdminView(r.getString(1),r.getString(2),r.getString(3),r.getLong(4)),id);if(rows.isEmpty())throw notFound();return rows.get(0);}
    private static IdentityAuthorizationException bad(){return new IdentityAuthorizationException("INVALID_REQUEST",400);} private static IdentityAuthorizationException notFound(){return new IdentityAuthorizationException("RESOURCE_NOT_FOUND",404);}
    public record UserView(String id,String displayName,String platformRole,String status,long version){}
    public record ProjectAdminView(String id,String name,String status,long permissionVersion){}
    public record CreateProject(String name,String initialAdminUserId){}
    public record UpdateUser(String platformRole,String status,long expectedVersion){}
    public record UpdateProject(String name,String status,long expectedVersion){}
}
