package com.argus.controlcenter.repository;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
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

/** 含历史数据的 V4→V5 升级，附带真实数据库队列 CAS/互斥与重开验证。 */
class TaskMigrationCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings={"",";MODE=MySQL"})
    void upgradesPopulatedH2V4(String mode) throws Exception {
        verify(new DriverManagerDataSource("jdbc:h2:mem:tasks-migration-"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1"+mode,"sa",""));
    }
    @Test
    @EnabledIfEnvironmentVariable(named="ARGUS_TEST_TASK_MYSQL_URL",matches=".+")
    void upgradesExplicitEmptyIsolatedMysqlV4() throws Exception {
        String url=System.getenv("ARGUS_TEST_TASK_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_refactor_tasks_[a-z0-9_]+(?:\\?[^#]*)?");
        DriverManagerDataSource source=new DriverManagerDataSource(url,System.getenv("ARGUS_TEST_TASK_MYSQL_USER"),System.getenv("ARGUS_TEST_TASK_MYSQL_PASSWORD"));
        assertThat(new JdbcTemplate(source).queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        verify(source);
    }
    void verify(DriverManagerDataSource source) throws Exception {
        Flyway.configure().dataSource(source).target("4").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO nodes(id,name,address,status) VALUES('old-node','old','http://127.0.0.1:19091','UNKNOWN')");
        jdbc.update("INSERT INTO instances(id,agent_instance_id,name,node_id,container_name,version,status,updated_at) VALUES('old-instance','mc01','old','old-node','mc01','old','STOPPED',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO tasks(id,instance_id,action,status,message,created_at,finished_at) VALUES('old-task','old-instance','START','SUCCEEDED','old simulation',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO logs(id,instance_id,log_timestamp,level,message) VALUES('old-log','old-instance',CURRENT_TIMESTAMP,'INFO','old evidence')");
        Flyway migration=Flyway.configure().dataSource(source).target("5").load();
        assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
        TaskRepository tasks=new TaskRepository(jdbc);
        Task old=tasks.findById("old-task").orElseThrow();
        assertThat(old.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(old.getInstanceId()).isEqualTo("old-instance");
        assertThat(old.getNodeId()).isEqualTo("old-node");assertThat(old.getAgentInstanceId()).isEqualTo("mc01");
        assertThat(old.getExecutionMode()).isEqualTo("LEGACY_MOCK");assertThat(old.getResultCode()).isEqualTo("LEGACY_MOCK");
        assertThat(old.getCommandId()).isNull();assertThat(old.getUpdatedAt()).isNotNull();assertThat(old.getAttempts()).isZero();
        assertThat(tasks.events("old-task")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT instance_id FROM logs WHERE id='old-log'",String.class)).isEqualTo("old-instance");
        assertThat(migration.migrate().migrationsExecuted).isZero();
        NodeRepository nodes=new NodeRepository(jdbc);
        InstanceRepository instances=new InstanceRepository(jdbc);
        assertThatThrownBy(()->new NodeService(nodes,instances,tasks,new LogRepository(jdbc)).delete("old-node")).hasMessage("NODE_HAS_TASK_HISTORY");

        SecurityProperties security=new SecurityProperties();security.setApiAuthRequired(true);security.setApiAccessToken("view");security.setApiControlToken("operator");
        AgentGatewayProperties gateway=new AgentGatewayProperties();gateway.setEnabled(true);gateway.setAllowControl(true);gateway.setAllowedHosts(Set.of("127.0.0.1"));gateway.setAuthToken("read");
        TaskControlProperties properties=new TaskControlProperties();properties.setControlEnabled(true);properties.setAllowedInstanceIds(Set.of("old-instance"));properties.setAgentControlToken("control");
        TaskControlPolicy policy=new TaskControlPolicy(security,gateway,properties);
        AgentCommandGateway http=new AgentCommandGateway(gateway,properties,new ObjectMapper());
        TaskQueueStore store=new TaskQueueStore(jdbc,tasks,nodes,instances,policy,http,properties,new DataSourceTransactionManager(source));
        Instance instance=instances.findById("old-instance").orElseThrow();instance.setDataSource("MOCK");instances.save(instance);
        String key=UUID.randomUUID().toString(),storeId=UUID.randomUUID().toString();
        AgentCommandGateway.Health health=new AgentCommandGateway.Health("agent-node",storeId,"MOCK",true);
        Task fresh=store.enqueue(instance,"http://127.0.0.1:19091","RESTART","MOCK",key,health);
        assertThat(store.enqueue(instance,"http://127.0.0.1:19091","RESTART","MOCK",key,health).getId()).isEqualTo(fresh.getId());
        assertThatThrownBy(()->store.enqueue(instance,"http://127.0.0.1:19091","RESTART","MOCK",UUID.randomUUID().toString(),health)).hasMessage("INSTANCE_HAS_UNRESOLVED_TASK");
        TaskCommand saved=new TaskRepository(new JdbcTemplate(source)).findCommand(fresh.getId()).orElseThrow();
        assertThat(saved.expiresAt().getNano()%1000).isZero();
        assertThat(saved.targetAddress()).isEqualTo("http://127.0.0.1:19091");assertThat(saved.storeId()).isEqualTo(storeId);
        assertThat(saved.task().getUpdatedAt()).isNotNull();assertThat(saved.agentAccepted()).isFalse();
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier=new CyclicBarrier(2);
            Callable<Optional<TaskQueueStore.Claim>> claim=()->{barrier.await();return store.claim();};
            Future<Optional<TaskQueueStore.Claim>> first=pool.submit(claim),second=pool.submit(claim);
            List<TaskQueueStore.Claim> claimed=new ArrayList<>();
            first.get(20,TimeUnit.SECONDS).ifPresent(claimed::add);second.get(20,TimeUnit.SECONDS).ifPresent(claimed::add);
            assertThat(claimed).hasSize(1);
            TaskQueueStore.Claim lease=claimed.get(0);
            assertThat(store.beginPost(lease)).isTrue();
            assertThat(store.finish(lease,TaskStatus.SUCCEEDED,"STATE_CONFIRMED",true)).isTrue();
            assertThat(store.finish(lease,TaskStatus.FAILED,"LATE")).isFalse();
        } finally {pool.shutdownNow();}
        Task restored=new TaskRepository(new JdbcTemplate(source)).findById(fresh.getId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo(TaskStatus.SUCCEEDED);assertThat(restored.getAttempts()).isEqualTo(1);
        assertThat(tasks.events(fresh.getId())).extracting(TaskEvent::sequence).containsExactly(1L,2L,3L);
        assertThat(tasks.findCommand(fresh.getId()).orElseThrow().agentAccepted()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks",Integer.class)).isZero();
        assertThat(instances.findById("old-instance").orElseThrow().getStatus()).isEqualTo(InstanceStatus.STOPPED);
        assertThat(migration.migrate().migrationsExecuted).isZero();
    }
}
