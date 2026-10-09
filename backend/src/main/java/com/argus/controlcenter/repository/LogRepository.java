package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.LogEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;
import com.argus.controlcenter.identity.IdentityConstants;

/** 日志摘要表访问层；limit 在入口处被限制到 1..500，防止一次响应过大。 */
@Repository
public class LogRepository {
    private final JdbcTemplate jdbc;
    public LogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private LogEntry map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new LogEntry(rs.getString("id"), rs.getString("instance_id"), rs.getTimestamp("log_timestamp").toInstant(), rs.getString("level"), rs.getString("message")); }
    public List<LogEntry> findByInstanceId(String instanceId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (instanceId == null) return jdbc.query("SELECT id,instance_id,log_timestamp,level,message FROM logs ORDER BY log_timestamp DESC LIMIT ?", this::map, safeLimit);
        return jdbc.query("SELECT id,instance_id,log_timestamp,level,message FROM logs WHERE instance_id=? ORDER BY log_timestamp DESC LIMIT ?", this::map, instanceId, safeLimit);
    }
    public List<LogEntry> findByInstanceId(String instanceId, int limit, String projectId) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (!hasProjectColumn()) return findByInstanceId(instanceId, safeLimit);
        if (instanceId == null) return jdbc.query("SELECT id,instance_id,log_timestamp,level,message FROM logs WHERE project_id=? ORDER BY log_timestamp DESC LIMIT ?", this::map, projectId, safeLimit);
        return jdbc.query("SELECT id,instance_id,log_timestamp,level,message FROM logs WHERE project_id=? AND instance_id=? ORDER BY log_timestamp DESC LIMIT ?", this::map, projectId, instanceId, safeLimit);
    }
    public LogEntry save(LogEntry value) {
        if (hasProjectColumn()) {
            String project = jdbc.query("SELECT project_id FROM instances WHERE id=?", rs -> rs.next() ? rs.getString(1) : null, value.getInstanceId());
            if (project == null || project.isBlank()) project = IdentityConstants.LEGACY_PROJECT_ID;
            jdbc.update("INSERT INTO logs (id,instance_id,log_timestamp,level,message,project_id) VALUES (?,?,?,?,?,?)", value.getId(), value.getInstanceId(), Timestamp.from(value.getTimestamp()), value.getLevel(), value.getMessage(), project);
            return value;
        }
        jdbc.update("INSERT INTO logs (id,instance_id,log_timestamp,level,message) VALUES (?,?,?,?,?)", value.getId(), value.getInstanceId(), Timestamp.from(value.getTimestamp()), value.getLevel(), value.getMessage()); return value;
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM logs", Long.class); }
    public int deleteByInstanceId(String instanceId) { return jdbc.update("DELETE FROM logs WHERE instance_id=?", instanceId); }
    private boolean hasProjectColumn() {
        try { return jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='LOGS' AND COLUMN_NAME='PROJECT_ID'", Integer.class) > 0; }
        catch (RuntimeException ignored) { return false; }
    }
}
