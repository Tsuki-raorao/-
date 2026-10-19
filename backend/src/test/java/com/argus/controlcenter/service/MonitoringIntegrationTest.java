package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.identity.IdentityConstants;
import com.argus.controlcenter.repository.NodeRepository;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:monitoring;DB_CLOSE_DELAY=-1;MODE=MySQL","argus.demo-data.enabled=false","argus.agent.enabled=false","argus.monitoring.enabled=false","argus.security.api-auth-required=true","argus.security.api-access-token=monitor-view-test"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitoringIntegrationTest {
    @Autowired MonitoringStore store;
    @Autowired MonitoringService monitor;
    @Autowired NodeRepository nodes;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc http;
    @Autowired com.argus.controlcenter.infra.TaskEventOutboxRepository outbox;
    private final String project=IdentityConstants.LEGACY_PROJECT_ID;

    @Test void monitoringEndpointsEnforceAuthenticationProjectAndMetricScope() throws Exception {
        http.perform(get("/api/auth/config")).andExpect(status().isOk());
        http.perform(get("/api/monitoring/summary")).andExpect(status().isUnauthorized());
        http.perform(get("/api/monitoring/summary").header("Authorization","Bearer monitor-view-test")).andExpect(status().isOk());
        http.perform(get("/api/monitoring/summary").param("projectId","another-project").header("Authorization","Bearer monitor-view-test")).andExpect(status().isForbidden());
        http.perform(get("/api/monitoring/history").param("resourceId","node").param("metric","anything").header("Authorization","Bearer monitor-view-test")).andExpect(status().isBadRequest());
    }

    @Test void deduplicatesUnknownDoesNotResolveAndValidSampleRecovers() {
        Instant now=Instant.now();String resource="cpu-case";
        store.observe(project,"CPU_HIGH",resource,"CPU","WARNING",95d,85,"high",now);
        store.observe(project,"CPU_HIGH",resource,"CPU","WARNING",96d,85,"still high",now.plusSeconds(30));
        String id=jdbc.queryForObject("SELECT id FROM monitor_alerts WHERE resource_id=?",String.class,resource);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert_events WHERE alert_id=?",Integer.class,id)).isEqualTo(1);
        store.observe(project,"CPU_HIGH",resource,"CPU","WARNING",null,85,"missing",now.plusSeconds(60));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE id=?",String.class,id)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT observation_state FROM monitor_alerts WHERE id=?",String.class,id)).isEqualTo("UNKNOWN");
        store.observe(project,"CPU_HIGH",resource,"CPU","WARNING",25d,85,"normal",now.plusSeconds(90));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE id=?",String.class,id)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert_events WHERE alert_id=?",Integer.class,id)).isEqualTo(2);
        store.observe(project,"CPU_HIGH",resource,"CPU","WARNING",99d,85,"again",now.plusSeconds(120));
        assertThat(jdbc.queryForObject("SELECT episodes FROM monitor_alerts WHERE id=?",Integer.class,id)).isEqualTo(2);
        assertThat(store.alerts("other-project")).isEmpty();
    }

    @Test void detectsFreshResourceAndStaleNodeAndPreservesUnknownSamples() {
        Instant now=Instant.now();String resource="monitor-node";
        var node=new Node(resource,"monitor check","127.0.0.1",NodeStatus.ONLINE,now);
        node.setProjectId(project);node.setDataSource("HOST");node.setMetricsStatus(MetricsStatus.AVAILABLE);
        node.setCpuPercent(96d);node.setMemoryBytes(950L);node.setMemoryTotalBytes(1000L);
        node.setSampledAt(now);node.setLastSuccessfulSyncAt(now);nodes.save(node);
        monitor.evaluate(now);
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='CPU_HIGH'",String.class,resource)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='MEMORY_HIGH'",String.class,resource)).isEqualTo("ACTIVE");
        monitor.evaluate(now.plusSeconds(181));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='NODE_OFFLINE'",String.class,resource)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT observation_state FROM monitor_alerts WHERE resource_id=? AND rule_key='CPU_HIGH'",String.class,resource)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT observation_state FROM monitor_alerts WHERE resource_id=? AND rule_key='MEMORY_HIGH'",String.class,resource)).isEqualTo("UNKNOWN");
        assertThat(store.history("other-project",resource,"CPU_PERCENT",10)).isEmpty();
        assertThat(store.history(project,resource,"CPU_PERCENT",10)).hasSize(2);
        node.setCpuPercent(1d);node.setMemoryBytes(100L);node.setSampledAt(now.plusSeconds(200));node.setLastSuccessfulSyncAt(now.plusSeconds(200));nodes.save(node);
        monitor.evaluate(now.plusSeconds(200));
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='CPU_HIGH'",String.class,resource)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='MEMORY_HIGH'",String.class,resource)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE resource_id=? AND rule_key='NODE_OFFLINE'",String.class,resource)).isEqualTo("RESOLVED");
    }

    @Test void samplesAreBoundedByRetentionAndInitialUnknownIsNotRecoveryEvent() {
        Instant now=Instant.now();
        store.observe(project,"RULE","unknown-case","unknown","WARNING",null,1,"missing",now);
        store.observe(project,"RULE","unknown-case","unknown","WARNING",0d,1,"known normal",now.plusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM monitor_alert_events e JOIN monitor_alerts a ON a.id=e.alert_id WHERE a.resource_id='unknown-case'",Integer.class)).isZero();
        store.sample(project,"old-sample","CPU_PERCENT",1d,"HOST",now.minusSeconds(8*86400));
        store.prune(now.minusSeconds(7*86400));
        assertThat(store.history(project,"old-sample","CPU_PERCENT",10)).isEmpty();
    }

    @Test @org.springframework.transaction.annotation.Transactional
    void taskWindowAndOutboxAgeTriggerThenRecoverWithoutChangingTaskFact() {
        Instant now=Instant.now();String nodeId="event-monitor-node",instance="event-monitor-instance",task="event-monitor-task";
        var node=new Node(nodeId,"Event monitor fixture","127.0.0.1",NodeStatus.UNKNOWN,null);node.setProjectId(project);nodes.save(node);
        jdbc.update("INSERT INTO instances(id,name,node_id,container_name,agent_instance_id,version,status,updated_at,project_id) VALUES(?,?,?,'fixture','fixture','test','STOPPED',?,?)",instance,"Event fixture",nodeId,java.sql.Timestamp.from(now),project);
        jdbc.update("INSERT INTO tasks(id,instance_id,action,status,created_at,updated_at,project_id,actor_user_id,auth_mode) VALUES(?,?,'START','FAILED',?,?,?,?,'LEGACY_TOKEN')",task,instance,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now),project,IdentityConstants.LEGACY_USER_ID);
        outbox.append(task,1,null,TaskStatus.FAILED,"test","VALIDATION",now.minusSeconds(120));
        monitor.evaluate(now);
        for(String rule:java.util.List.of("TASK_FAILURES","OUTBOX_BACKLOG"))
            assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE project_id=? AND rule_key=? AND resource_id='project'",String.class,project,rule)).isEqualTo("ACTIVE");
        jdbc.update("UPDATE task_event_outbox SET status='PUBLISHED',published_at=? WHERE task_id=?",java.sql.Timestamp.from(now),task);
        monitor.evaluate(now.plusSeconds(301));
        for(String rule:java.util.List.of("TASK_FAILURES","OUTBOX_BACKLOG"))
            assertThat(jdbc.queryForObject("SELECT state FROM monitor_alerts WHERE project_id=? AND rule_key=? AND resource_id='project'",String.class,project,rule)).isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id=?",String.class,task)).isEqualTo("FAILED");
    }
}
