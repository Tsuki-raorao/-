package com.argus.controlcenter.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地身份联调夹具。默认关闭，只有明确打开 ARGUS_IDENTITY_TEST_DATA_ENABLED 才会写入数据库。
 * 夹具使用固定 issuer/subject 和 UUID，重复启动只补缺失记录，不覆盖人工改动。
 */
@Component
public class IdentityTestDataInitializer {
    public static final String TEST_ISSUER = "https://argus.test-idp.invalid";
    public static final String ADMIN_ID = "10000000-0000-0000-0000-000000000001";
    public static final String OPERATOR_ID = "10000000-0000-0000-0000-000000000002";
    public static final String VIEWER_ID = "10000000-0000-0000-0000-000000000003";
    public static final String PROJECT_ID = "20000000-0000-0000-0000-000000000001";
    public static final String SECOND_PROJECT_ID = "20000000-0000-0000-0000-000000000002";

    private final JdbcTemplate jdbc;
    public IdentityTestDataInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void seed() {
        user(ADMIN_ID, "admin", "测试管理员", "ADMIN");
        user(OPERATOR_ID, "operator", "测试操作员", "USER");
        user(VIEWER_ID, "viewer", "测试查看者", "USER");
        project(PROJECT_ID, "Argus 联调项目");
        project(SECOND_PROJECT_ID, "Argus 权限隔离项目");
        member(PROJECT_ID, ADMIN_ID, "ADMIN");
        member(PROJECT_ID, OPERATOR_ID, "OPERATOR");
        member(PROJECT_ID, VIEWER_ID, "VIEWER");
        member(SECOND_PROJECT_ID, ADMIN_ID, "ADMIN");
        member(SECOND_PROJECT_ID, VIEWER_ID, "VIEWER");
    }

    private void user(String id, String subject, String displayName, String platformRole) {
        if (exists("SELECT COUNT(*) FROM users WHERE id=?", id)) return;
        jdbc.update("INSERT INTO users(id,issuer,subject,display_name,status,platform_role,version_no,created_at,updated_at) VALUES(?,?,?,?,'ACTIVE',?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, TEST_ISSUER, subject, displayName, platformRole);
    }
    private void project(String id, String name) {
        if (exists("SELECT COUNT(*) FROM projects WHERE id=?", id)) return;
        jdbc.update("INSERT INTO projects(id,name,status,permission_version,created_at,updated_at) VALUES(?,?, 'ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, name);
    }
    private void member(String projectId, String userId, String role) {
        if (exists("SELECT COUNT(*) FROM project_members WHERE project_id=? AND user_id=?", projectId, userId)) return;
        jdbc.update("INSERT INTO project_members(project_id,user_id,role,version_no,created_at,updated_at) VALUES(?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", projectId, userId, role);
    }
    private boolean exists(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args) > 0; }
}
