package com.argus.controlcenter.domain;

import java.time.Instant;

/** 节点上的可管理服务实例，例如名为“零壹”的 Minecraft 容器。 */
public class Instance {
    /** 实例唯一标识。 */
    private String id;
    /** 用户可读名称。 */
    private String name;
    /** 所属节点 ID，对应 nodes.id。 */
    private String nodeId;
    /** 实例对应的容器名称。 */
    private String containerName;
    /** 服务版本，例如 1.21.1-NeoForge。 */
    private String version;
    /** 控制中心记录的生命周期状态。 */
    private InstanceStatus status;
    /** 最近一次状态变化时间。 */
    private Instant updatedAt;
    /** Agent 最近一次报告的 CPU 瞬时占用百分比。 */
    private double cpuPercent;
    /** Agent 最近一次报告的内存占用字节数。 */
    private long memoryBytes;
    /** 服务报告的玩家数量；非游戏服务通常为 0。 */
    private int playerCount;

    public Instance() { }
    public Instance(String id, String name, String nodeId, String containerName, String version, InstanceStatus status, Instant updatedAt) {
        this(id, name, nodeId, containerName, version, status, updatedAt, 0, 0, 0);
    }
    public Instance(String id, String name, String nodeId, String containerName, String version, InstanceStatus status,
                    Instant updatedAt, double cpuPercent, long memoryBytes, int playerCount) {
        this.id = id; this.name = name; this.nodeId = nodeId; this.containerName = containerName; this.version = version; this.status = status; this.updatedAt = updatedAt;
        this.cpuPercent = cpuPercent; this.memoryBytes = memoryBytes; this.playerCount = playerCount;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getContainerName() { return containerName; }
    public void setContainerName(String containerName) { this.containerName = containerName; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public InstanceStatus getStatus() { return status; }
    public void setStatus(InstanceStatus status) { this.status = status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public double getCpuPercent() { return cpuPercent; }
    public void setCpuPercent(double cpuPercent) { this.cpuPercent = cpuPercent; }
    public long getMemoryBytes() { return memoryBytes; }
    public void setMemoryBytes(long memoryBytes) { this.memoryBytes = memoryBytes; }
    public int getPlayerCount() { return playerCount; }
    public void setPlayerCount(int playerCount) { this.playerCount = playerCount; }
}
