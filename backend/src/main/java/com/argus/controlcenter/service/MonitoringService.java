package com.argus.controlcenter.service;

import com.argus.controlcenter.config.MonitoringProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.identity.IdentityConstants;
import com.argus.controlcenter.repository.NodeRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 只观察已有事实，不修改任务/节点，不自动执行修复。 */
@Service
public class MonitoringService {
    private final NodeRepository nodes; private final JdbcTemplate jdbc; private final MonitoringStore store;
    private final MonitoringProperties properties; private final ObjectProvider<RabbitAdmin> admin;
    private final boolean mqEnabled; private final String queue;
    public MonitoringService(NodeRepository nodes,JdbcTemplate jdbc,MonitoringStore store,MonitoringProperties properties,ObjectProvider<RabbitAdmin> admin,
            @Value("${argus.messaging.enabled:false}") boolean mqEnabled,@Value("${argus.messaging.consumer-queue:argus.task-events.consumer.v1}") String queue){
        this.nodes=nodes;this.jdbc=jdbc;this.store=store;this.properties=properties;this.admin=admin;this.mqEnabled=mqEnabled;this.queue=queue;
    }
    @Scheduled(fixedDelayString="${argus.monitoring.interval-ms:30000}",initialDelayString="${argus.monitoring.initial-delay-ms:15000}")
    public void tick(){if(properties.isEnabled())evaluate(Instant.now());}
    public void evaluate(Instant now){
        for(Node node:nodes.findAll()) {
            String project=node.getProjectId(); if(project==null)continue;
            boolean fresh=node.getLastSuccessfulSyncAt()!=null && !node.getLastSuccessfulSyncAt().isAfter(now.plusSeconds(5))
                    && node.getLastSuccessfulSyncAt().isAfter(now.minusSeconds(properties.getStaleSeconds()));
            boolean real="HOST".equals(node.getDataSource());
            Double offline=!real?null:(!fresh||node.getStatus()==NodeStatus.OFFLINE?1d:0d);
            store.observe(project,"NODE_OFFLINE",node.getId(),"节点离线或快照过期","CRITICAL",offline,1,"按最近完整同步与节点状态判断",now);
            boolean metrics=real && fresh && node.getSampledAt()!=null && !node.getSampledAt().isAfter(now.plusSeconds(5))
                    && node.getSampledAt().isAfter(now.minusSeconds(properties.getStaleSeconds()))
                    && (node.getMetricsStatus()==MetricsStatus.AVAILABLE||node.getMetricsStatus()==MetricsStatus.PARTIAL);
            Double cpu=metrics?node.getCpuPercent():null;
            Double memory=metrics && node.getMemoryBytes()!=null && node.getMemoryTotalBytes()!=null && node.getMemoryTotalBytes()>0
                    ?node.getMemoryBytes()*100d/node.getMemoryTotalBytes():null;
            store.sample(project,node.getId(),"CPU_PERCENT",cpu,node.getDataSource(),now);
            store.sample(project,node.getId(),"MEMORY_PERCENT",memory,node.getDataSource(),now);
            store.observe(project,"CPU_HIGH",node.getId(),"CPU 使用率过高","WARNING",cpu,properties.getCpuThreshold(),"未知指标不视为恢复",now);
            store.observe(project,"MEMORY_HIGH",node.getId(),"内存使用率过高","WARNING",memory,properties.getMemoryThreshold(),"未知指标不视为恢复",now);
        }
        for(String project:jdbc.queryForList("SELECT id FROM projects WHERE status='ACTIVE'",String.class)) {
            Double failures=jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE project_id=? AND status IN ('FAILED','UNKNOWN') AND updated_at>=?",Double.class,project,Timestamp.from(now.minusSeconds(properties.getTaskWindowSeconds())));
            Double pending=jdbc.queryForObject("SELECT COUNT(*) FROM task_event_outbox o JOIN tasks t ON t.id=o.task_id WHERE t.project_id=? AND o.status<>'PUBLISHED' AND o.created_at<?",Double.class,project,Timestamp.from(now.minusSeconds(properties.getOutboxAgeSeconds())));
            store.observe(project,"TASK_FAILURES","project", "近期任务失败或结果未知","WARNING",failures,1,"滑动窗口内 FAILED/UNKNOWN 任务；窗口外告警恢复不改变原任务",now);
            store.observe(project,"OUTBOX_BACKLOG","project","待发事件积压","WARNING",pending,1,"超过等待阈值仍未发布的 Outbox",now);
            store.sample(project,"project","OUTBOX_BACKLOG",pending,"MYSQL",now);
            store.sample(project,"project","TASK_FAILURES",failures,"MYSQL",now);
        }
        // 共享队列是平台指标，仅归入系统遗留项目，不复制到各租户。
        Double backlog=queueCount(queue),dead=queueCount("argus.task-events.dead");
        String system=IdentityConstants.LEGACY_PROJECT_ID;
        store.observe(system,"MQ_QUEUE_BACKLOG","platform","消息队列积压","WARNING",backlog,properties.getQueueThreshold(),"仅统计 ready 消息，Broker 不可用时为未知",now);
        store.observe(system,"MQ_DEAD_LETTERS","platform","死信队列非空","CRITICAL",dead,1,"死信保留待人工分析，不自动重放",now);
        store.sample(system,"platform","MQ_QUEUE_BACKLOG",backlog,mqEnabled?"RABBITMQ":"DISABLED",now);
        store.sample(system,"platform","MQ_DEAD_LETTERS",dead,mqEnabled?"RABBITMQ":"DISABLED",now);
        store.prune(now.minus(properties.getRetentionDays(),ChronoUnit.DAYS));
    }
    private Double queueCount(String name){
        if(!mqEnabled)return null;
        try{var rabbit=admin.getIfAvailable(); if(rabbit==null)return null; var info=rabbit.getQueueInfo(name);return info==null?null:(double)info.getMessageCount();}
        catch(RuntimeException unavailable){return null;}
    }
}
