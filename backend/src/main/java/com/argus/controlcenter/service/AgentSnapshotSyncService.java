package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.Instance;
import com.argus.controlcenter.domain.InstanceStatus;
import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import com.argus.controlcenter.repository.InstanceRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 定时把只读 Agent 的快照写入控制中心数据库。
 *
 * <p>这是从“能访问 Agent”到“前端能看到持久化实例”的最小桥梁。同步只
 * 使用 Agent 的 GET 接口，不执行 Docker 动作；节点不可达时标记 OFFLINE，
 * 不删除历史实例，避免短暂网络故障造成数据丢失。</p>
 */
@Service
public class AgentSnapshotSyncService {
    private final NodeService nodes;
    private final AgentGatewayService gateway;
    private final com.argus.controlcenter.config.AgentGatewayProperties gatewayProperties;
    private final InstanceRepository instances;

    public AgentSnapshotSyncService(NodeService nodes, AgentGatewayService gateway,
                                    com.argus.controlcenter.config.AgentGatewayProperties gatewayProperties,
                                    InstanceRepository instances) {
        this.nodes = nodes;
        this.gateway = gateway;
        this.gatewayProperties = gatewayProperties;
        this.instances = instances;
    }

    /** 首次延迟执行，之后按固定间隔刷新，避免应用启动阶段阻塞健康接口。 */
    @Scheduled(
            initialDelayString = "${argus.agent.sync-initial-delay-ms:5000}",
            fixedDelayString = "${argus.agent.sync-interval-ms:60000}")
    public void syncAll() {
        // 网关关闭时不应把本地节点误标为离线；本地开发和只使用 H2 时直接跳过。
        if (!gatewayProperties.isEnabled() || !gatewayProperties.isReadOnly()) return;
        for (Node node : nodes.findAll()) syncNode(node);
    }

    private void syncNode(Node node) {
        try {
            JsonNode health = gateway.health(node.getId());
            nodes.heartbeat(node.getId(), NodeStatus.ONLINE,
                    number(health, "hostCpuPercent"),
                    longNumber(health, "hostMemoryBytes"),
                    longNumber(health, "hostMemoryTotalBytes"));
            JsonNode snapshot = gateway.instances(node.getId());
            if (snapshot.isArray()) {
                for (JsonNode item : snapshot) saveInstance(node, item);
            }
        } catch (Exception e) {
            // 只记录可观察状态，不把远端错误正文写入数据库，避免泄露敏感信息。
            try { nodes.heartbeat(node.getId(), NodeStatus.OFFLINE); } catch (Exception ignored) { }
        }
    }

    private void saveInstance(Node node, JsonNode item) {
        String id = text(item, "instanceId");
        if (id.isBlank() || !id.matches("[A-Za-z0-9_.-]{1,64}")) return;
        String state = text(item, "status").toUpperCase();
        InstanceStatus status;
        try { status = InstanceStatus.valueOf(state); }
        catch (IllegalArgumentException e) { status = InstanceStatus.UNKNOWN; }
        String container = text(item, "container");
        if (container.isBlank()) container = id;
        Instance previous = instances.findById(id).orElse(null);
        String version = textOr(item, "version", "");
        if (version.isBlank() || version.equalsIgnoreCase("unknown")) {
            version = previous == null ? "unknown" : previous.getVersion();
        }
        double cpuPercent = number(item, "cpuPercent");
        long memoryBytes = longNumber(item, "memoryBytes");
        int playerCount = (int) Math.max(0, longNumber(item, "players"));
        instances.save(new Instance(id, textOr(item, "name", id), node.getId(), container,
                version, status, Instant.now(), cpuPercent, memoryBytes, playerCount));
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        String value = text(node, field);
        return value.isBlank() ? fallback : value;
    }

    private double number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Math.max(0, value.asDouble()) : 0;
    }

    private long longNumber(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? Math.max(0, value.asLong()) : 0;
    }
}
