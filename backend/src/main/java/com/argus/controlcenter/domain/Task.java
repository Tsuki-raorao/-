package com.argus.controlcenter.domain;

import java.time.Instant;

/** 一次面向服务实例的控制任务及其最终结果。 */
public class Task {
    /** 中央任务唯一标识；浏览器幂等键和 Agent commandId 另行持久保存。 */
    private String id;
    /** 被操作的实例 ID。 */
    private String instanceId;
    /** 操作名称，例如 START、STOP、RESTART。 */
    private String action;
    /** 任务状态机当前状态。 */
    private TaskStatus status;
    /** 面向用户的执行说明或错误原因。 */
    private String message;
    /** 任务创建时间。 */
    private Instant createdAt;
    /** 任务结束时间；运行中为 null。 */
    private Instant finishedAt;
    private String nodeId;
    private String agentInstanceId;
    private String commandId;
    private String executionMode = "LEGACY_MOCK";
    private String requestedBy = "legacy";
    private Instant updatedAt;
    private int attempts;
    private String resultCode;

    public Task() { }
    public Task(String id, String instanceId, String action, TaskStatus status, String message, Instant createdAt, Instant finishedAt) {
        this.id=id; this.instanceId=instanceId; this.action=action; this.status=status; this.message=message; this.createdAt=createdAt; this.finishedAt=finishedAt;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String value) { nodeId = value; }
    public String getAgentInstanceId() { return agentInstanceId; }
    public void setAgentInstanceId(String value) { agentInstanceId = value; }
    public String getCommandId() { return commandId; }
    public void setCommandId(String value) { commandId = value; }
    public String getExecutionMode() { return executionMode; }
    public void setExecutionMode(String value) { executionMode = value; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String value) { requestedBy = value; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant value) { updatedAt = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public String getResultCode() { return resultCode; }
    public void setResultCode(String value) { resultCode = value; }
}
