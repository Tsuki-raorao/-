package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.NodeRepository;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 只读采集：先验证完整清单，再原子持久化；失败保留上次成功证据。 */
@Service
public class AgentSnapshotSyncService {
    private final NodeRepository nodes;
    private final AgentGatewayService gateway;
    private final AgentGatewayProperties properties;
    private final AgentSnapshotStore store;
    private final AgentSnapshotParser parser = new AgentSnapshotParser();

    public AgentSnapshotSyncService(NodeRepository nodes, AgentGatewayService gateway,
                                    AgentGatewayProperties properties, AgentSnapshotStore store) {
        this.nodes = nodes; this.gateway = gateway; this.properties = properties; this.store = store;
    }

    @Scheduled(initialDelayString = "${argus.agent.sync-initial-delay-ms:5000}",
            fixedDelayString = "${argus.agent.sync-interval-ms:60000}")
    public void syncAll() {
        if (!properties.isEnabled() || !properties.isReadOnly()) return;
        for (Node node : nodes.findAll()) syncNode(node.getId());
    }

    /** 当前单进程顺序采集；同一进程重复触发不能交叉覆盖采集时间。 */
    public synchronized void syncNode(String nodeId) {
        if (!properties.isEnabled() || !properties.isReadOnly()) return;
        Node node = nodes.findById(nodeId).orElse(null);
        if (node == null) return;
        node.setLastCheckedAt(Instant.now());
        if (!nodes.updateExisting(node)) return;
        AgentSnapshotParser.Sample health;
        try { health = parser.health(gateway.health(nodeId)); }
        catch (IllegalArgumentException invalid) { fail(node, false, "INVALID_HEALTH"); return; }
        catch (RuntimeException failed) { fail(node, false, "HEALTH_REQUEST_FAILED"); return; }

        node.setStatus(NodeStatus.ONLINE);
        node.setLastHeartbeat(Instant.now());
        if (!health.source().equals("LEGACY")) {
            node.setCpuPercent(health.cpu());
            node.setMemoryBytes(health.memory());
            node.setMemoryTotalBytes(health.total());
            node.setSampledAt(health.at());
        }
        node.setDataSource(health.source());
        node.setMetricsStatus(health.status());
        node.setSyncStatus(SyncStatus.PARTIAL);
        node.setSyncErrorCode(null);
        if (!nodes.updateExisting(node)) return;

        java.util.List<Instance> snapshot;
        try { snapshot = parser.instances(nodeId, gateway.instances(nodeId), Instant.now()); }
        catch (IllegalArgumentException invalid) { fail(node, true, "INVALID_INSTANCES"); return; }
        catch (RuntimeException failed) { fail(node, true, "INSTANCES_REQUEST_FAILED"); return; }
        try { store.save(node, snapshot, Instant.now()); }
        catch (RuntimeException failed) {
            // store 的事务已回滚；恢复内存对象中的成功时间，避免失败标记把它带回库。
            nodes.findById(nodeId).ifPresent(previous -> fail(previous, true, "PERSISTENCE_FAILED"));
        }
    }

    private void fail(Node node, boolean healthSucceeded, String code) {
        node.setStatus(healthSucceeded ? NodeStatus.ONLINE : NodeStatus.OFFLINE);
        node.setSyncStatus(healthSucceeded ? SyncStatus.PARTIAL : SyncStatus.FAILED);
        node.setSyncErrorCode(code);
        nodes.updateExisting(node);
    }
}
