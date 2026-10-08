package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

/** 先校验整份响应，任何结构错误都不能留下半份持久化快照。 */
final class AgentSnapshotParser {
    record Sample(String source, Instant at, MetricsStatus status, Double cpu, Long memory, Long total, Integer players) { }

    Sample health(JsonNode json) {
        require(json != null && json.isObject());
        require(Set.of("ONLINE", "UP").contains(text(json, "status")));
        if (json.has("protocolVersion")) require("1.1".equals(text(json, "protocolVersion")));
        return sample(json, true);
    }

    List<Instance> instances(String nodeId, JsonNode json, Instant observedAt) {
        require(json != null && json.isArray() && json.size() <= 10000);
        Set<String> ids = new HashSet<>();
        List<Instance> result = new ArrayList<>();
        for (JsonNode item : json) {
            require(item.isObject());
            String id = text(item, "instanceId");
            require(id.matches("[A-Za-z0-9_.-]{1,64}") && ids.add(id));
            String rawStatus = text(item, "status");
            require(!rawStatus.isEmpty());
            InstanceStatus status;
            try { status = InstanceStatus.valueOf(rawStatus.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException ignored) { status = InstanceStatus.UNKNOWN; }
            Instance value = new Instance(UUID.randomUUID().toString(), bounded(item, "name", id, 128), nodeId,
                    bounded(item, "container", id, 128), bounded(item, "version", "unknown", 64), status, observedAt);
            value.setAgentInstanceId(id);
            value.setLastSeenAt(observedAt);
            Sample sample = sample(item, false);
            value.setDataSource(sample.source());
            value.setSampledAt(sample.at());
            value.setMetricsStatus(sample.status());
            value.setCpuPercent(sample.cpu());
            value.setMemoryBytes(sample.memory());
            value.setPlayerCount(sample.players());
            result.add(value);
        }
        // 稳定锁定顺序降低并发批次更新时的死锁机会。
        result.sort(Comparator.comparing(Instance::getAgentInstanceId));
        return result;
    }

    private Sample sample(JsonNode json, boolean host) {
        boolean metadata = json.has("dataSource") || json.has("sampledAt") || json.has("metricsStatus") || json.has("protocolVersion");
        if (!metadata) return new Sample("LEGACY", null, MetricsStatus.UNKNOWN, null, null, null, null);
        require(json.has("dataSource") && json.has("sampledAt") && json.has("metricsStatus"));
        String source = text(json, "dataSource");
        require((host ? Set.of("HOST", "MOCK") : Set.of("DOCKER", "MOCK")).contains(source));
        Instant at;
        MetricsStatus status;
        try { at = Instant.parse(text(json, "sampledAt")); status = MetricsStatus.valueOf(text(json, "metricsStatus")); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid agent snapshot"); }
        // 允许有限时钟偏移，但未来时间不能使旧指标无限保持新鲜。
        require(!at.isAfter(Instant.now().plusSeconds(300)));
        if (status == MetricsStatus.UNKNOWN) return new Sample(source, at, status, null, null, null, null);
        Double cpu = decimal(json, host ? "hostCpuPercent" : "cpuPercent", host ? 100 : 99999999.99);
        Long memory = integer(json, host ? "hostMemoryBytes" : "memoryBytes", Long.MAX_VALUE);
        Long total = host ? integer(json, "hostMemoryTotalBytes", Long.MAX_VALUE) : null;
        Long players = host ? null : integer(json, "players", Integer.MAX_VALUE);
        if (host && memory != null && total != null) require(memory <= total);
        int count = (cpu == null ? 0 : 1) + (memory == null ? 0 : 1) + (host && total != null ? 1 : 0);
        int expected = host ? 3 : 2;
        MetricsStatus actual = count == 0 ? MetricsStatus.UNAVAILABLE : count == expected ? MetricsStatus.AVAILABLE : MetricsStatus.PARTIAL;
        require(status == actual);
        return new Sample(source, at, status, cpu, memory, total, players == null ? null : players.intValue());
    }

    private Double decimal(JsonNode json, String field, double max) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return null;
        require(value.isNumber() && Double.isFinite(value.asDouble()) && value.asDouble() >= 0 && value.asDouble() <= max);
        return value.asDouble();
    }
    private Long integer(JsonNode json, String field, long max) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return null;
        require(value.isIntegralNumber() && value.canConvertToLong() && value.asLong() >= 0 && value.asLong() <= max);
        return value.asLong();
    }
    private String bounded(JsonNode json, String field, String fallback, int max) {
        String value = text(json, field);
        if (value.isEmpty()) value = fallback;
        require(value.length() <= max);
        return value;
    }
    private String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        if (value == null || value.isNull()) return "";
        require(value.isTextual());
        return value.asText().trim();
    }
    private void require(boolean condition) {
        if (!condition) throw new IllegalArgumentException("invalid agent snapshot");
    }
}
