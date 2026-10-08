package com.argus.controlcenter.repository;

import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 仅本机显式隔离库：覆盖恢复旧表与新 schema 默认不一致，以及已应用 V5 不重跑。 */
class MysqlForeignKeyCollationTest {
    @Test
    @EnabledIfEnvironmentVariable(named="ARGUS_TEST_TASK_UNICODE_MYSQL_URL",matches=".+")
    void restoresUnicodeParentsIntoDifferentDefaultSchema() throws Exception {
        verifyFresh("ARGUS_TEST_TASK_UNICODE_MYSQL_URL",false);
    }
    @Test
    @EnabledIfEnvironmentVariable(named="ARGUS_TEST_TASK_MIXED_MYSQL_URL",matches=".+")
    void eachForeignKeyInheritsItsOwnParentTypeCharsetAndCollation() throws Exception {
        verifyFresh("ARGUS_TEST_TASK_MIXED_MYSQL_URL",true);
    }
    @Test
    @EnabledIfEnvironmentVariable(named="ARGUS_TEST_TASK_APPLIED_MYSQL_URL",matches=".+")
    void alreadyAppliedOriginalV5RemainsUnchanged() {
        DriverManagerDataSource source=source("ARGUS_TEST_TASK_APPLIED_MYSQL_URL");
        JdbcTemplate jdbc=new JdbcTemplate(source);
        // 本用例使用原 V5 开发库的隔离副本，不能拿空库建表代替已安装历史。
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(5);
        List<Map<String,Object>> history=jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank");
        List<Map<String,Object>> columns=jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION");
        List<Map<String,Object>> tasks=jdbc.queryForList("SELECT * FROM tasks ORDER BY id");
        List<Map<String,Object>> events=jdbc.queryForList("SELECT * FROM task_events ORDER BY task_id,event_sequence");
        Flyway flyway=Flyway.configure().dataSource(source).target("5").load();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM flyway_schema_history ORDER BY installed_rank")).isEqualTo(history);
        assertThat(jdbc.queryForList("SELECT TABLE_NAME,COLUMN_NAME,COLUMN_TYPE,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME,ORDINAL_POSITION")).isEqualTo(columns);
        assertThat(jdbc.queryForList("SELECT * FROM tasks ORDER BY id")).isEqualTo(tasks);
        assertThat(jdbc.queryForList("SELECT * FROM task_events ORDER BY task_id,event_sequence")).isEqualTo(events);
    }
    private void verifyFresh(String environment,boolean mixed) throws Exception {
        DriverManagerDataSource source=source(environment);
        JdbcTemplate jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Map<String,String> parentDefinitions=new LinkedHashMap<>();
        new TaskMigrationCompatibilityTest().verify(source, database -> {
            String schema=database.queryForObject("SELECT DATABASE()",String.class);
            assertThat(schema).matches("argus_refactor_tasks_[a-z0-9_]+");
            // 先以历史 unicode 默认建立 V4，再模拟恢复到 MySQL 8 新默认的数据库。
            assertThat(definition(database,"instances","id")).contains("utf8mb4_unicode_ci");
            database.execute("ALTER DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            if(mixed) database.execute("ALTER TABLE tasks MODIFY COLUMN id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
            parentDefinitions.put("tasks",definition(database,"tasks","id"));
            parentDefinitions.put("instances",definition(database,"instances","id"));
        });
        assertThat(definition(jdbc,"tasks","id")).isEqualTo(parentDefinitions.get("tasks"));
        assertThat(definition(jdbc,"instances","id")).isEqualTo(parentDefinitions.get("instances"));
        assertThat(definition(jdbc,"task_queue","task_id")).isEqualTo(parentDefinitions.get("tasks"));
        assertThat(definition(jdbc,"task_events","task_id")).isEqualTo(parentDefinitions.get("tasks"));
        assertThat(definition(jdbc,"instance_task_locks","task_id")).isEqualTo(parentDefinitions.get("tasks"));
        assertThat(definition(jdbc,"instance_task_locks","instance_id")).isEqualTo(parentDefinitions.get("instances"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('task_queue','task_events','instance_task_locks') AND REFERENCED_TABLE_NAME IS NOT NULL",Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT instance_id FROM tasks WHERE id='old-task'",String.class)).isEqualTo("old-instance");
        assertThat(jdbc.queryForObject("SELECT instance_id FROM logs WHERE id='old-log'",String.class)).isEqualTo("old-instance");
    }
    private DriverManagerDataSource source(String environment) {
        String url=System.getenv(environment);
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_refactor_tasks_[a-z0-9_]+(?:\\?[^#]*)?");
        return new DriverManagerDataSource(url,System.getenv("ARGUS_TEST_TASK_MYSQL_USER"),System.getenv("ARGUS_TEST_TASK_MYSQL_PASSWORD"));
    }
    private String definition(JdbcTemplate jdbc,String table,String column) {
        return jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE,'/',CHARACTER_SET_NAME,'/',COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",String.class,table,column);
    }
}
