package db.migration;

import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * 身份与项目边界迁移。历史行进入显式 SYSTEM_LEGACY／legacy project，不改写任务正文、回执或哈希。
 * 所有可由数据库推断的父列类型保留，避免 MySQL 外键字符集／排序规则漂移。
 */
public class V7__identity_projects extends BaseJavaMigration {
    private static final String LEGACY_PROJECT = "00000000-0000-0000-0000-000000000001";
    private static final String LEGACY_USER = "00000000-0000-0000-0000-000000000002";
    private static final String GATE = "00000000-0000-0000-0000-000000000001";
    private boolean mysql;
    private String projectRef;
    private String userRef;

    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        mysql = c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        projectRef = mysql ? "VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "VARCHAR(36)";
        userRef = projectRef;
        createIdentityTables(c);
        seedLegacy(c);
        addResourceProject(c, "nodes", "node");
        addResourceProject(c, "instances", "instance");
        addResourceProject(c, "logs", "log");
        addResourceProject(c, "tasks", "task");
        addTaskActors(c);
        addResolutionScope(c);
        addEventActors(c);
        createPermitTables(c);
    }

    private void createIdentityTables(Connection c) throws SQLException {
        // MySQL utf8mb4 下两个 512 字符列的联合索引最多需要 4096 字节，超过 InnoDB 3072 字节限制。
        // 保留完整 issuer/subject 值，用生成的 SHA-256 二进制列实现等值唯一约束；H2 继续使用直接联合唯一约束。
        String userIdentityConstraint = mysql
                ? ", identity_hash BINARY(32) GENERATED ALWAYS AS (UNHEX(SHA2(CONCAT(issuer, CHAR(0), subject), 256))) STORED, CONSTRAINT uq_users_issuer_subject_hash UNIQUE(identity_hash)"
                : ", CONSTRAINT uq_users_issuer_subject UNIQUE(issuer,subject)";
        execute(c, "CREATE TABLE users (id " + userRef + " PRIMARY KEY, issuer VARCHAR(512) NOT NULL, subject VARCHAR(512) NOT NULL, display_name VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, platform_role VARCHAR(32) NOT NULL, version_no BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL" + userIdentityConstraint + ")");
        execute(c, "CREATE TABLE projects (id " + projectRef + " PRIMARY KEY, name VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL, permission_version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL)");
        execute(c, "CREATE TABLE project_members (project_id " + projectRef + " NOT NULL, user_id " + userRef + " NOT NULL, role VARCHAR(16) NOT NULL, version_no BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, PRIMARY KEY(project_id,user_id), CONSTRAINT fk_member_project FOREIGN KEY(project_id) REFERENCES projects(id), CONSTRAINT fk_member_user FOREIGN KEY(user_id) REFERENCES users(id))");
        execute(c, "CREATE TABLE identity_config_gate (id " + projectRef + " PRIMARY KEY, mode VARCHAR(24) NOT NULL, oidc_activated BOOLEAN NOT NULL DEFAULT FALSE, bootstrap_issuer VARCHAR(512) NULL, bootstrap_subject VARCHAR(512) NULL, bootstrap_user_id " + userRef + " NULL, updated_at TIMESTAMP(6) NOT NULL)");
    }

    private void seedLegacy(Connection c) throws SQLException {
        Instant now = Instant.now();
        execute(c, "INSERT INTO users(id,issuer,subject,display_name,status,platform_role,version_no,created_at,updated_at) VALUES('" + LEGACY_USER + "','legacy','" + LEGACY_USER + "','Legacy migration owner','ACTIVE','SYSTEM_LEGACY',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute(c, "INSERT INTO projects(id,name,status,permission_version,created_at,updated_at) VALUES('" + LEGACY_PROJECT + "','Legacy resources','ACTIVE',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute(c, "INSERT INTO project_members(project_id,user_id,role,version_no,created_at,updated_at) VALUES('" + LEGACY_PROJECT + "','" + LEGACY_USER + "','ADMIN',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        execute(c, "INSERT INTO identity_config_gate(id,mode,oidc_activated,updated_at) VALUES('" + GATE + "','LEGACY_TOKEN',FALSE,CURRENT_TIMESTAMP)");
    }

    private void addResourceProject(Connection c, String table, String prefix) throws SQLException {
        addColumn(c, table, "project_id", projectRef);
        execute(c, "UPDATE " + table + " SET project_id='" + LEGACY_PROJECT + "' WHERE project_id IS NULL");
        setNotNull(c, table, "project_id", projectRef);
        addIndex(c, "idx_" + prefix + "_project", table, "project_id");
        addForeignKey(c, "fk_" + prefix + "_project", table, "project_id", "projects", "id");
    }

    private void addTaskActors(Connection c) throws SQLException {
        addColumn(c, "tasks", "actor_user_id", userRef);
        addColumn(c, "tasks", "auth_mode", "VARCHAR(24)");
        execute(c, "UPDATE tasks SET actor_user_id='" + LEGACY_USER + "', auth_mode='LEGACY_TOKEN' WHERE actor_user_id IS NULL");
        setNotNull(c, "tasks", "actor_user_id", userRef);
        setNotNull(c, "tasks", "auth_mode", "VARCHAR(24)");
        addForeignKey(c, "fk_tasks_actor", "tasks", "actor_user_id", "users", "id");
        addIndex(c, "idx_tasks_project_created", "tasks", "project_id,created_at,id");
        dropUniqueIndexesOn(c, "tasks", "idempotency_key");
        addUnique(c, "uq_tasks_project_actor_key", "tasks", "project_id,actor_user_id,idempotency_key");
    }

    private void addResolutionScope(Connection c) throws SQLException {
        addColumn(c, "task_resolutions", "project_id", projectRef);
        addColumn(c, "task_resolutions", "actor_user_id", userRef);
        addColumn(c, "task_resolutions", "auth_mode", "VARCHAR(24)");
        addColumn(c, "task_resolutions", "active_authorization_id", projectRef);
        execute(c, "UPDATE task_resolutions r SET project_id=(SELECT t.project_id FROM tasks t WHERE t.id=r.task_id), actor_user_id=(SELECT t.actor_user_id FROM tasks t WHERE t.id=r.task_id), auth_mode=(SELECT t.auth_mode FROM tasks t WHERE t.id=r.task_id) WHERE r.project_id IS NULL");
        setNotNull(c, "task_resolutions", "project_id", projectRef);
        setNotNull(c, "task_resolutions", "actor_user_id", userRef);
        setNotNull(c, "task_resolutions", "auth_mode", "VARCHAR(24)");
        addForeignKey(c, "fk_resolutions_project", "task_resolutions", "project_id", "projects", "id");
        addForeignKey(c, "fk_resolutions_actor", "task_resolutions", "actor_user_id", "users", "id");
        addIndex(c, "idx_resolutions_project_created", "task_resolutions", "project_id,created_at,id");
        dropUniqueIndexesOn(c, "task_resolutions", "request_key");
        addUnique(c, "uq_resolution_project_actor_key", "task_resolutions", "project_id,actor_user_id,request_key");
    }

    private void addEventActors(Connection c) throws SQLException {
        addColumn(c, "task_events", "actor_user_id", userRef);
        addColumn(c, "task_resolution_events", "actor_user_id", userRef);
        addForeignKey(c, "fk_task_event_actor", "task_events", "actor_user_id", "users", "id");
        addForeignKey(c, "fk_resolution_event_actor", "task_resolution_events", "actor_user_id", "users", "id");
    }

    private void createPermitTables(Connection c) throws SQLException {
        String taskRef = referenceType(c, "tasks");
        String resolutionRef = referenceType(c, "task_resolutions");
        execute(c, "CREATE TABLE resolution_authorizations (id " + projectRef + " PRIMARY KEY, resolution_id " + resolutionRef + " NOT NULL, project_id " + projectRef + " NOT NULL, actor_user_id " + userRef + " NOT NULL, auth_mode VARCHAR(24) NOT NULL, request_key VARCHAR(36) NOT NULL, request_hash CHAR(64) NOT NULL, reason VARCHAR(1000) NOT NULL, acknowledge_no_replay BOOLEAN NOT NULL, acknowledge_residual_risk BOOLEAN NOT NULL, kind VARCHAR(16) NOT NULL, created_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_auth_resolution FOREIGN KEY(resolution_id) REFERENCES task_resolutions(id), CONSTRAINT fk_auth_project FOREIGN KEY(project_id) REFERENCES projects(id), CONSTRAINT fk_auth_actor FOREIGN KEY(actor_user_id) REFERENCES users(id), CONSTRAINT uq_auth_project_actor_key UNIQUE(project_id,actor_user_id,request_key))");
        execute(c, "ALTER TABLE task_resolutions ADD CONSTRAINT fk_resolution_active_authorization FOREIGN KEY(active_authorization_id) REFERENCES resolution_authorizations(id)");
        execute(c, "CREATE TABLE task_dispatch_permits (id " + projectRef + " PRIMARY KEY, task_id " + taskRef + " NOT NULL, project_id " + projectRef + " NOT NULL, actor_user_id " + userRef + " NOT NULL, auth_mode VARCHAR(24) NOT NULL, permission_version BIGINT NOT NULL, attempt_no INT NOT NULL, lease_owner VARCHAR(64) NOT NULL, issued_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_permit_task FOREIGN KEY(task_id) REFERENCES tasks(id), CONSTRAINT fk_permit_project FOREIGN KEY(project_id) REFERENCES projects(id), CONSTRAINT fk_permit_actor FOREIGN KEY(actor_user_id) REFERENCES users(id), CONSTRAINT uq_permit_attempt UNIQUE(task_id,attempt_no))");
        execute(c, "CREATE TABLE review_dispatch_permits (id " + projectRef + " PRIMARY KEY, resolution_id " + resolutionRef + " NOT NULL, authorization_id " + projectRef + " NOT NULL, project_id " + projectRef + " NOT NULL, actor_user_id " + userRef + " NOT NULL, auth_mode VARCHAR(24) NOT NULL, permission_version BIGINT NOT NULL, attempt_no INT NOT NULL, lease_owner VARCHAR(64) NOT NULL, issued_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_review_permit_resolution FOREIGN KEY(resolution_id) REFERENCES task_resolutions(id), CONSTRAINT fk_review_permit_authorization FOREIGN KEY(authorization_id) REFERENCES resolution_authorizations(id), CONSTRAINT fk_review_permit_project FOREIGN KEY(project_id) REFERENCES projects(id), CONSTRAINT fk_review_permit_actor FOREIGN KEY(actor_user_id) REFERENCES users(id), CONSTRAINT uq_review_permit_attempt UNIQUE(resolution_id,attempt_no))");
    }

    private void addColumn(Connection c, String table, String column, String type) throws SQLException {
        if (!hasColumn(c, table, column)) execute(c, "ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
    }
    private void setNotNull(Connection c, String table, String column, String type) throws SQLException {
        if (mysql) execute(c, "ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + type + " NOT NULL");
        else execute(c, "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
    }
    private boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (ResultSet r = c.getMetaData().getColumns(null, null, table, column)) { return r.next(); }
    }
    private void addIndex(Connection c, String name, String table, String columns) throws SQLException { if (!hasIndex(c, table, name)) execute(c, "CREATE INDEX " + name + " ON " + table + "(" + columns + ")"); }
    private void addUnique(Connection c, String name, String table, String columns) throws SQLException { if (!hasIndex(c, table, name)) execute(c, "CREATE UNIQUE INDEX " + name + " ON " + table + "(" + columns + ")"); }
    private boolean hasIndex(Connection c, String table, String name) throws SQLException { try (ResultSet r=c.getMetaData().getIndexInfo(null,null,table,false,false)) { while(r.next()) if(name.equalsIgnoreCase(r.getString("INDEX_NAME"))) return true; } return false; }
    private void addForeignKey(Connection c, String name, String table, String column, String parent, String parentColumn) throws SQLException { if (!hasForeignKey(c, table, name)) execute(c, "ALTER TABLE " + table + " ADD CONSTRAINT " + name + " FOREIGN KEY(" + column + ") REFERENCES " + parent + "(" + parentColumn + ")"); }
    private boolean hasForeignKey(Connection c, String table, String name) throws SQLException { try (ResultSet r=c.getMetaData().getImportedKeys(null,null,table)) { while(r.next()) if(name.equalsIgnoreCase(r.getString("FK_NAME"))) return true; } return false; }
    private void dropUniqueIndexesOn(Connection c, String table, String column) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (ResultSet r=c.getMetaData().getIndexInfo(null,null,table,true,false)) {
            while(r.next()) { String name=r.getString("INDEX_NAME"), col=r.getString("COLUMN_NAME"); if(name!=null && col!=null && column.equalsIgnoreCase(col)) names.add(name); }
        }
        for(String name:names) {
            // MySQL 元数据在部分版本可能返回同一索引多行；第一次删除后再次确认，避免重复 DROP。
            if (!hasIndex(c, table, name)) continue;
            if(mysql) execute(c,"ALTER TABLE "+table+" DROP INDEX `"+name.replace("`","``")+"`"); else execute(c,"DROP INDEX "+name);
        }
    }
    private String referenceType(Connection c, String table) throws SQLException {
        try (PreparedStatement p=c.prepareStatement("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='id'")) {
            if(!mysql) return "VARCHAR(64)"; p.setString(1,table); try(ResultSet r=p.executeQuery()) { if(!r.next()) throw new SQLException("missing parent id: "+table); String type=r.getString(1); long len=r.getLong(2); String cs=r.getString(3),co=r.getString(4); if(!type.matches("(?i)varchar|char")||len<1||len>65535||cs==null||co==null||!cs.matches("[A-Za-z0-9_]+")||!co.matches("[A-Za-z0-9_]+")) throw new SQLException("unsafe parent id metadata: "+table); return type+"("+len+") CHARACTER SET "+cs+" COLLATE "+co; }
        }
    }
    private static void execute(Connection c, String sql) throws SQLException { try(Statement s=c.createStatement()){s.execute(sql);} }
}
