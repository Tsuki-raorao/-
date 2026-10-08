package com.argus.agent.model;

/** Agent 返回的单个容器实例状态和只读资源指标。 */
public record InstanceStatus(String instanceId, String name, String container, String status,
                             Double cpuPercent, Long memoryBytes, Integer players, String checkedAt,
                             String dataSource, String sampledAt, String metricsStatus) { }
