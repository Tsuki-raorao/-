package com.argus.controlcenter.domain;

import java.time.Instant;

/** 控制中心保存的一条服务日志摘要。大量原始日志应进入 Loki 等日志系统。 */
public class LogEntry {
    /** 日志记录唯一标识。 */
    private String id;
    /** 日志所属实例 ID。 */
    private String instanceId;
    /** 日志发生时间。 */
    private Instant timestamp;
    /** 日志级别，例如 INFO、WARN、ERROR。 */
    private String level;
    /** 清洗后的日志内容。 */
    private String message;

    public LogEntry() { }
    public LogEntry(String id, String instanceId, Instant timestamp, String level, String message) {
        this.id=id; this.instanceId=instanceId; this.timestamp=timestamp; this.level=level; this.message=message;
    }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String instanceId) { this.instanceId = instanceId; }
    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
