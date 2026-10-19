package com.argus.controlcenter.service;

import com.argus.controlcenter.config.MonitoringProperties;
import com.argus.controlcenter.domain.MetricsStatus;
import com.argus.controlcenter.domain.NodeStatus;
import com.argus.controlcenter.identity.IdentityConstants;
import com.argus.controlcenter.repository.NodeRepository;
import java.time.Instant;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;
import static org.mockito.Mockito.*;

class MySqlMonitoringIntegrationTest {
    @Test void preservesExistingFactsAndEvaluatesMysqlAlertLifecycle() {
        String url=System.getenv("ARGUS_TEST_MQ_MYSQL_UPGRADE_URL");
        assumeTrue(url!=null&&!url.isBlank(),"isolated migrated MySQL not configured");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/argus_test_stage1(?:\\?[^#]*)?");
        var source=new DriverManagerDataSource(url,System.getenv("ARGUS_TEST_MQ_MYSQL_USER"),System.getenv("ARGUS_TEST_MQ_MYSQL_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        var tasks=jdbc.queryForList("SELECT * FROM tasks ORDER BY id");
        var outbox=jdbc.queryForList("SELECT * FROM task_event_outbox ORDER BY id");
        // 仅在此显式隔离库中，首次开发失败且未建出监控表时修复失败版本；不对业务库执行 repair。
        if(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='11' AND success=0",Integer.class)>0){
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'monitor_%'",Integer.class)).isZero();
            Flyway.configure().dataSource(source).load().repair();
        }
        Flyway.configure().dataSource(source).load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM tasks ORDER BY id")).isEqualTo(tasks);
        assertThat(jdbc.queryForList("SELECT * FROM task_event_outbox ORDER BY id")).isEqualTo(outbox);
        var nodes=new NodeRepository(jdbc);var node=nodes.findById("mq-check-node").orElseThrow();
        Instant now=Instant.now();node.setDataSource("HOST");node.setMetricsStatus(MetricsStatus.AVAILABLE);
        node.setStatus(NodeStatus.ONLINE);node.setCpuPercent(97d);node.setMemoryBytes(950L);node.setMemoryTotalBytes(1000L);
        node.setLastSuccessfulSyncAt(now);node.setSampledAt(now);nodes.save(node);
        var store=new MonitoringStore(jdbc);var properties=new MonitoringProperties();
        @SuppressWarnings("unchecked") ObjectProvider<RabbitAdmin> admin=mock(ObjectProvider.class);
        var service=new MonitoringService(nodes,jdbc,store,properties,admin,false,"unused");
        var tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.executeWithoutResult(s->service.evaluate(now));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id='mq-check-node' AND rule_key='CPU_HIGH'",String.class)).isEqualTo("ACTIVE");
        tx.executeWithoutResult(s->service.evaluate(now.plusSeconds(181)));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id='mq-check-node' AND rule_key='CPU_HIGH'",String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT observation_state FROM monitor_alerts WHERE resource_id='mq-check-node' AND rule_key='CPU_HIGH'",String.class)).isEqualTo("UNKNOWN");
        node.setCpuPercent(2d);node.setMemoryBytes(100L);node.setLastSuccessfulSyncAt(now.plusSeconds(200));node.setSampledAt(now.plusSeconds(200));nodes.save(node);
        tx.executeWithoutResult(s->service.evaluate(now.plusSeconds(200)));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id='mq-check-node' AND rule_key='CPU_HIGH'",String.class)).isEqualTo("RESOLVED");
        assertThat(store.history(IdentityConstants.LEGACY_PROJECT_ID,"mq-check-node","CPU_PERCENT",10)).hasSizeGreaterThanOrEqualTo(3);
        assertThat(store.history("another-project","mq-check-node","CPU_PERCENT",10)).isEmpty();
    }
}
