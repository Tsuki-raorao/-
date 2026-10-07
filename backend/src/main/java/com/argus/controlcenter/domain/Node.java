package com.argus.controlcenter.domain;

import java.time.Instant;

/** 被 Argus 管理的服务器节点。节点可以承载多个服务实例。 */
public class Node {
    /** 节点唯一标识，由控制中心生成。 */
    private String id;
    /** 给用户看的节点名称。 */
    private String name;
    /** Agent 的访问地址或节点主机地址。 */
    private String address;
    /** 当前连接状态。 */
    private NodeStatus status;
    /** 最近一次心跳时间；从未收到心跳时为 null。 */
    private Instant lastHeartbeat;
    /** Agent 最近上报的主机 CPU 百分比。 */
    private double cpuPercent;
    /** Agent 最近上报的主机已用内存字节数。 */
    private long memoryBytes;
    /** Agent 最近上报的主机内存总字节数。 */
    private long memoryTotalBytes;

    public Node() { }
    public Node(String id, String name, String address, NodeStatus status, Instant lastHeartbeat) {
        this(id, name, address, status, lastHeartbeat, 0, 0, 0);
    }
    public Node(String id, String name, String address, NodeStatus status, Instant lastHeartbeat,
                double cpuPercent, long memoryBytes, long memoryTotalBytes) {
        this.id = id; this.name = name; this.address = address; this.status = status; this.lastHeartbeat = lastHeartbeat;
        this.cpuPercent = cpuPercent; this.memoryBytes = memoryBytes; this.memoryTotalBytes = memoryTotalBytes;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public NodeStatus getStatus() { return status; }
    public void setStatus(NodeStatus status) { this.status = status; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    public double getCpuPercent() { return cpuPercent; }
    public void setCpuPercent(double cpuPercent) { this.cpuPercent = Math.max(0, cpuPercent); }
    public long getMemoryBytes() { return memoryBytes; }
    public void setMemoryBytes(long memoryBytes) { this.memoryBytes = Math.max(0, memoryBytes); }
    public long getMemoryTotalBytes() { return memoryTotalBytes; }
    public void setMemoryTotalBytes(long memoryTotalBytes) { this.memoryTotalBytes = Math.max(0, memoryTotalBytes); }
}
