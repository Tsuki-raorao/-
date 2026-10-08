package com.argus.controlcenter.repository;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import static org.assertj.core.api.Assertions.*;

/** 真正带 V5 历史和 UNKNOWN 互斥升级；不是只检查新空库建表。 */
class TaskReviewMigrationCompatibilityTest {
    @ParameterizedTest @ValueSource(strings={"",";MODE=MySQL"})
    void preservesPopulatedV5OnH2(String mode)throws Exception {
        verify(new DriverManagerDataSource("jdbc:h2:mem:review-migration-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"+mode,"sa",""),false);
    }
    @Test @EnabledIfEnvironmentVariable(named="ARGUS_TEST_REVIEW_MYSQL_URL",matches=".+")
    void inheritsActualParentMetadataAndPreservesHistoryOnIsolatedMysql()throws Exception {
        String url=System.getenv("ARGUS_TEST_REVIEW_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_refactor_review_[a-z0-9_]+(?:\\?[^#]*)?");
        DriverManagerDataSource source=new DriverManagerDataSource(url,System.getenv("ARGUS_TEST_TASK_MYSQL_USER"),System.getenv("ARGUS_TEST_TASK_MYSQL_PASSWORD"));
        JdbcTemplate jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        verify(source,true);
    }
    private void verify(DriverManagerDataSource source,boolean mysql)throws Exception {
        new TaskMigrationCompatibilityTest().verify(source,jdbc->{
            if(mysql) {
                String schema=jdbc.queryForObject("SELECT DATABASE()",String.class);
                assertThat(schema).matches("argus_refactor_review_[a-z0-9_]+");
                jdbc.execute("ALTER TABLE tasks MODIFY COLUMN id VARCHAR(96) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
                jdbc.execute("ALTER DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
        });
        JdbcTemplate jdbc=new JdbcTemplate(source);TaskRepository tasks=new TaskRepository(jdbc);
        Instant created=Instant.parse("2026-10-01T00:00:00Z"),finished=created.plusSeconds(5);
        Task task=new Task("unknown-history","old-instance","RESTART",TaskStatus.UNKNOWN,"EXECUTION_UNCERTAIN",created,finished);
        task.setCommandId(UUID.randomUUID().toString());task.setNodeId("old-node");task.setAgentInstanceId("mc01");task.setExecutionMode("MOCK");task.setUpdatedAt(finished);task.setResultCode("EXECUTION_UNCERTAIN");task.setRequestedBy("operator");
        TaskCommand original=new TaskCommand(task,UUID.randomUUID().toString(),"http://127.0.0.1:19091","agent-node",UUID.randomUUID().toString(),created.plusSeconds(10),2,true);
        tasks.insert(original);jdbc.update("INSERT INTO instance_task_locks(instance_id,task_id) VALUES('old-instance','unknown-history')");
        tasks.appendEvent(task.getId(),2,TaskStatus.RUNNING,TaskStatus.UNKNOWN,"worker","EXECUTION_UNCERTAIN",finished);
        Map<String,List<Map<String,Object>>> before=new LinkedHashMap<>();
        for(String table:List.of("nodes","instances","tasks","task_events","instance_task_locks","task_queue","logs"))before.put(table,jdbc.queryForList("SELECT * FROM "+table));
        Flyway flyway=Flyway.configure().dataSource(source).target("6").load();assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        for(var entry:before.entrySet())assertThat(jdbc.queryForList("SELECT * FROM "+entry.getKey())).containsExactlyInAnyOrderElementsOf(entry.getValue());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_resolutions",Integer.class)).isZero();
        if(mysql) {
            assertThat(definition(jdbc,"tasks","id")).isEqualTo("varchar(96)/ascii/ascii_bin");
            assertThat(definition(jdbc,"task_resolutions","task_id")).isEqualTo(definition(jdbc,"tasks","id"));
            assertThat(definition(jdbc,"task_resolution_queue","resolution_id")).isEqualTo(definition(jdbc,"task_resolutions","id"));
            assertThat(definition(jdbc,"task_resolution_events","resolution_id")).isEqualTo(definition(jdbc,"task_resolutions","id"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN ('task_resolutions','task_resolution_queue','task_resolution_events') AND REFERENCED_TABLE_NAME IS NOT NULL",Integer.class)).isEqualTo(3);
        }
        SecurityProperties security=new SecurityProperties();security.setApiAuthRequired(true);security.setApiAccessToken("view");security.setApiControlToken("operator");
        AgentGatewayProperties gateway=new AgentGatewayProperties();gateway.setEnabled(true);gateway.setAllowedHosts(Set.of("127.0.0.1"));gateway.setAuthToken("agent-view");
        TaskControlProperties taskConfig=new TaskControlProperties();taskConfig.setAgentControlToken("agent-control");
        TaskReviewProperties reviewConfig=new TaskReviewProperties();reviewConfig.setEnabled(true);reviewConfig.setAllowedInstanceIds(Set.of("old-instance"));
        TaskReviewPolicy policy=new TaskReviewPolicy(security,gateway,taskConfig,reviewConfig);
        ObjectMapper json=new ObjectMapper().findAndRegisterModules();TaskResolutionRepository resolutions=new TaskResolutionRepository(jdbc,json);
        TaskReviewStore store=new TaskReviewStore(jdbc,resolutions,tasks,new NodeRepository(jdbc),new InstanceRepository(jdbc),new AgentCommandGateway(gateway,taskConfig,json),policy,reviewConfig,new DataSourceTransactionManager(source));
        ReviewRequest intent=new ReviewRequest(ReviewRequest.DECISION,"人工核对","未重放原命令 😀",true,true);String key=UUID.randomUUID().toString();
        TaskResolution first=store.submit(task.getId(),key,intent,true);
        assertThat(store.submit(task.getId(),key,intent,true).id()).isEqualTo(first.id());
        ExecutorService pool=Executors.newFixedThreadPool(2);CyclicBarrier barrier=new CyclicBarrier(2);List<TaskReviewStore.Claim> claims=new ArrayList<>();
        try {
            Callable<Optional<TaskReviewStore.Claim>> claim=()->{barrier.await();return store.claim();};
            Future<Optional<TaskReviewStore.Claim>> one=pool.submit(claim),two=pool.submit(claim);
            one.get(20,TimeUnit.SECONDS).ifPresent(claims::add);two.get(20,TimeUnit.SECONDS).ifPresent(claims::add);
        }finally{pool.shutdownNow();}
        assertThat(claims).hasSize(1);TaskReviewStore.Claim stale=claims.get(0);
        jdbc.update("UPDATE task_resolution_queue SET lease_until=?",Timestamp.from(Instant.parse("2000-01-01T00:00:00Z")));TaskReviewStore.Claim fresh=store.claim().orElseThrow();
        assertThat(store.failure(stale,"STALE",false)).isFalse();assertThat(store.beforePost(stale)).isFalse();
        assertThat(store.failure(fresh,"REVIEW_AGENT_UNAVAILABLE",false)).isTrue();
        assertThat(store.submit(task.getId(),key,intent,true).id()).isEqualTo(first.id());
        assertThat(store.ownsInstance(original)).isTrue();
        assertThat(new TaskResolutionRepository(new JdbcTemplate(source),json).byTask(task.getId()).orElseThrow().request()).isEqualTo(intent);
        assertThat(tasks.findById(task.getId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.UNKNOWN);
        assertThat(tasks.events(task.getId())).hasSize(1);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }
    private String definition(JdbcTemplate jdbc,String table,String column) {
        return jdbc.queryForObject("SELECT CONCAT(COLUMN_TYPE,'/',CHARACTER_SET_NAME,'/',COLLATION_NAME) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?",String.class,table,column);
    }
}
