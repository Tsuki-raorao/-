package com.argus.agent.model;

/** Agent 返回的单个容器实例状态和只读资源指标。 */
public record InstanceStatus(String instanceId, String name, String container, String status,
                             double cpuPercent, long memoryBytes, int players, String checkedAt) { }
