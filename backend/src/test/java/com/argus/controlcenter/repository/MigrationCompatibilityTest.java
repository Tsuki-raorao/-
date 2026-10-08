package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 用含业务数据的 V3 库升级，不能用空库建表测试替代迁移验收。 */
class MigrationCompatibilityTest {
    @ParameterizedTest
    @ValueSource(strings = {"", ";MODE=MySQL"})
    void upgradesPopulatedH2KeepingExistingForeignKeys(String mode) throws Exception {
        verifyMigration(new DriverManagerDataSource("jdbc:h2:mem:migration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1" + mode, "sa", ""));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ARGUS_TEST_MYSQL_URL", matches = ".+")
    void upgradesExplicitIsolatedMysql() throws Exception {
        String url = System.getenv("ARGUS_TEST_MYSQL_URL");
        // 只允许显式指定的本机一次性库；不能误用生产配置，且要求库内完全为空。
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_(?:test|refactor)_[a-z0-9_]+(?:\\?[^#]*)?");
        DriverManagerDataSource source = new DriverManagerDataSource(url, System.getenv("ARGUS_TEST_MYSQL_USER"), System.getenv("ARGUS_TEST_MYSQL_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        verifyMigration(source);
    }

    private void verifyMigration(DriverManagerDataSource source) throws Exception {
        Flyway.configure().dataSource(source).target("3").load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO nodes(id,name,address,status,cpu_percent) VALUES ('node-old','old','127.0.0.1','UNKNOWN',12)");
        jdbc.update("INSERT INTO instances(id,name,node_id,container_name,version,status,updated_at,cpu_percent) VALUES ('mc01','old','node-old','mc01','old','RUNNING',CURRENT_TIMESTAMP,34)");
        jdbc.update("INSERT INTO tasks(id,instance_id,action,status,created_at) VALUES ('task-old','mc01','START','SUCCEEDED',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO logs(id,instance_id,log_timestamp,level,message) VALUES ('log-old','mc01',CURRENT_TIMESTAMP,'INFO','historical log')");
        Flyway latest = Flyway.configure().dataSource(source).target("4").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        InstanceRepository repository = new InstanceRepository(jdbc);
        Instance old = repository.findById("mc01").orElseThrow();
        assertThat(old.getAgentInstanceId()).isEqualTo("mc01");
        assertThat(old.getDataSource()).isEqualTo("LEGACY");
        assertThat(old.getMetricsStatus()).isEqualTo(MetricsStatus.UNKNOWN);
        assertThat(old.getSampledAt()).isNull();
        assertThat(old.getLastSeenAt()).isNull();
        assertThat(old.getCpuPercent()).isEqualTo(34);
        NodeRepository nodeRepository = new NodeRepository(jdbc);
        Node timeNode = nodeRepository.findById("node-old").orElseThrow();
        Instant precise = Instant.parse("2026-01-01T00:00:00.123456Z");
        timeNode.setLastCheckedAt(precise); timeNode.setLastSuccessfulSyncAt(precise); timeNode.setSampledAt(precise);
        timeNode.setCpuPercent(null); timeNode.setMemoryBytes(null); timeNode.setMemoryTotalBytes(null);
        nodeRepository.save(timeNode);
        Node reopened = nodeRepository.findById("node-old").orElseThrow();
        assertThat(reopened.getLastCheckedAt()).isEqualTo(precise);
        assertThat(reopened.getLastSuccessfulSyncAt()).isEqualTo(precise);
        assertThat(reopened.getSampledAt()).isEqualTo(precise);
        assertThat(reopened.getCpuPercent()).isNull();
        assertThat(jdbc.queryForObject("SELECT instance_id FROM tasks WHERE id='task-old'", String.class)).isEqualTo("mc01");
        assertThat(jdbc.queryForObject("SELECT instance_id FROM logs WHERE id='log-old'", String.class)).isEqualTo("mc01");
        Instance refresh = value("node-old", "mc01");
        refresh.setSampledAt(precise); refresh.setLastSeenAt(precise);
        repository.saveDiscovered(refresh);
        assertThat(refresh.getId()).isEqualTo("mc01");
        assertThat(repository.findById("mc01").orElseThrow().getLastSeenAt()).isEqualTo(precise);
        assertThat(repository.findById("mc01").orElseThrow().getSampledAt()).isEqualTo(precise);
        assertThat(repository.findById("mc01").orElseThrow().getCpuPercent()).isNull();
        assertThat(repository.findById("mc01").orElseThrow().getMemoryBytes()).isNull();
        assertThat(repository.findById("mc01").orElseThrow().getPlayerCount()).isNull();
        new NodeRepository(jdbc).save(new Node("node-new", "new", "127.0.0.1", NodeStatus.UNKNOWN, null));
        repository.saveDiscovered(value("node-new", "mc01"));
        repository.saveDiscovered(value("node-old", "MC01"));
        assertThat(repository.count()).isEqualTo(3);
        assertThatThrownBy(() -> repository.save(value("node-old", "mc01"))).isInstanceOf(DuplicateKeyException.class);
        assertThat(latest.migrate().migrationsExecuted).isZero();
        assertThat(new InstanceRepository(new JdbcTemplate(source)).findById("mc01")).isPresent();
        verifyConcurrentDiscovery(source);
    }

    /** 强制两个事务同时观察到“不存在”，验证唯一键冲突重读，而非顺序调用侥幸通过。 */
    private void verifyConcurrentDiscovery(DriverManagerDataSource source) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        CyclicBarrier bothMissing = new CyclicBarrier(2);
        ThreadLocal<Boolean> firstLookup = ThreadLocal.withInitial(() -> true);
        InstanceRepository racing = new InstanceRepository(jdbc) {
            @Override public Optional<Instance> findByNodeAndAgentId(String node, String agent) {
                if (firstLookup.get()) {
                    firstLookup.set(false);
                    try { bothMissing.await(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new IllegalStateException(e); }
                    return Optional.empty();
                }
                return super.findByNodeAndAgentId(node, agent);
            }
        };
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Callable<String> discover = () -> {
                TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
                transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                return transaction.execute(status -> racing.saveDiscovered(value("node-old", "concurrent")).getId());
            };
            Future<String> a = workers.submit(discover), b = workers.submit(discover);
            assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(b.get(20, TimeUnit.SECONDS));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM instances WHERE node_id='node-old' AND agent_instance_id='concurrent'", Integer.class)).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }

    private Instance value(String node, String remote) {
        Instance value = new Instance(UUID.randomUUID().toString(), remote, node, remote, "test", InstanceStatus.RUNNING, Instant.now());
        value.setAgentInstanceId(remote);
        return value;
    }
}
