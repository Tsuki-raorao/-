package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.Task;
import com.argus.controlcenter.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

/** 控制任务表访问层。任务状态由 TaskService 驱动。 */
@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 将 tasks 表的一行转换为 Task，并保留未结束任务的 null finished_at。 */
    private Task map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { Timestamp finished=rs.getTimestamp("finished_at"); return new Task(rs.getString("id"), rs.getString("instance_id"), rs.getString("action"), TaskStatus.valueOf(rs.getString("status")), rs.getString("message"), rs.getTimestamp("created_at").toInstant(), finished == null ? null : finished.toInstant()); }
    public List<Task> findAll() { return jdbc.query("SELECT id,instance_id,action,status,message,created_at,finished_at FROM tasks ORDER BY created_at DESC", this::map); }
    public Optional<Task> findById(String id) { return jdbc.query("SELECT id,instance_id,action,status,message,created_at,finished_at FROM tasks WHERE id=?", this::map, id).stream().findFirst(); }
    public Task save(Task value) { Object finished=value.getFinishedAt() == null ? null : Timestamp.from(value.getFinishedAt()); int updated=jdbc.update("UPDATE tasks SET instance_id=?,action=?,status=?,message=?,created_at=?,finished_at=? WHERE id=?", value.getInstanceId(), value.getAction(), value.getStatus().name(), value.getMessage(), Timestamp.from(value.getCreatedAt()), finished, value.getId()); if (updated == 0) jdbc.update("INSERT INTO tasks (id,instance_id,action,status,message,created_at,finished_at) VALUES (?,?,?,?,?,?,?)", value.getId(), value.getInstanceId(), value.getAction(), value.getStatus().name(), value.getMessage(), Timestamp.from(value.getCreatedAt()), finished); return value; }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM tasks", Long.class); }
    public int deleteByInstanceId(String instanceId) { return jdbc.update("DELETE FROM tasks WHERE instance_id=?", instanceId); }
}
