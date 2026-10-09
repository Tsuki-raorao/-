package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.argus.controlcenter.repository.JdbcValues.*;
import com.argus.controlcenter.identity.IdentityConstants;

/** 任务本体与不可变投递绑定；控制任务只由事务化 TaskQueueStore 写入。 */
@Repository
public class TaskRepository {
    private final JdbcTemplate jdbc;
    public TaskRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private Task map(ResultSet rs, int row) throws SQLException {
        Task value = new Task(rs.getString("id"), rs.getString("instance_id"), rs.getString("action"),
                TaskStatus.valueOf(rs.getString("status")), rs.getString("message"), instant(rs,"created_at"), instant(rs,"finished_at"));
        value.setNodeId(rs.getString("node_id")); value.setAgentInstanceId(rs.getString("agent_instance_id"));
        value.setCommandId(rs.getString("command_id")); value.setExecutionMode(rs.getString("execution_mode"));
        value.setRequestedBy(rs.getString("requested_by")); value.setUpdatedAt(instant(rs,"updated_at"));
        value.setProjectId(optional(rs,"project_id")); value.setActorUserId(optional(rs,"actor_user_id"));
        String authMode=optional(rs,"auth_mode"); if(authMode!=null) value.setAuthMode(authMode);
        value.setAttempts(rs.getInt("attempts")); value.setResultCode(rs.getString("result_code"));
        return value;
    }
    private static String optional(ResultSet rs,String column) {
        try { return rs.getString(column); } catch (SQLException ignored) { return null; }
    }
    private TaskCommand command(ResultSet rs, int row) throws SQLException {
        return new TaskCommand(map(rs,row),rs.getString("idempotency_key"),rs.getString("target_address"),
                rs.getString("agent_node_id"),rs.getString("store_id"),instant(rs,"expires_at"),rs.getLong("version_no"),rs.getBoolean("agent_accepted"));
    }
    public List<Task> findAll() { return jdbc.query("SELECT * FROM tasks ORDER BY created_at DESC,id LIMIT 100", this::map); }
    public List<Task> findAll(String projectId) {
        if(!hasColumn("project_id")) return findAll();
        return jdbc.query("SELECT * FROM tasks WHERE project_id=? ORDER BY created_at DESC,id LIMIT 100", this::map,projectId);
    }
    public Optional<Task> findById(String id) { return findCommand(id).map(TaskCommand::task); }
    public Optional<TaskCommand> findCommand(String id) { return jdbc.query("SELECT * FROM tasks WHERE id=?",this::command,id).stream().findFirst(); }
    public Optional<TaskCommand> findByKey(String key) { return jdbc.query("SELECT * FROM tasks WHERE idempotency_key=?",this::command,key).stream().findFirst(); }
    /** 身份模式的幂等查账；筛选在数据库完成，避免跨项目/主体泄漏相同 request key。 */
    public Optional<TaskCommand> findByKey(String projectId,String actorUserId,String key) {
        if(!hasColumn("project_id")) return Optional.empty();
        return jdbc.query("SELECT * FROM tasks WHERE project_id=? AND actor_user_id=? AND idempotency_key=?",this::command,projectId,actorUserId,key).stream().findFirst();
    }
    public TaskCommand lock(String id) { return jdbc.query("SELECT * FROM tasks WHERE id=? FOR UPDATE",this::command,id).stream().findFirst().orElseThrow(); }
    public void insert(TaskCommand command) {
        Task t = command.task();
        String identity = hasColumn("project_id")
                ? ",project_id,actor_user_id,auth_mode" : "";
        String placeholders = hasColumn("project_id") ? ",?,?,?" : "";
        String sql="INSERT INTO tasks(id,instance_id,action,status,message,created_at,finished_at,node_id,agent_instance_id,command_id,idempotency_key,execution_mode,requested_by,updated_at,attempts,result_code,target_address,agent_node_id,store_id,expires_at,version_no"+identity+") VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"+placeholders+")";
        if(hasColumn("project_id")) {
            jdbc.update(sql,
                t.getId(),t.getInstanceId(),t.getAction(),t.getStatus().name(),t.getMessage(),timestamp(t.getCreatedAt()),timestamp(t.getFinishedAt()),
                t.getNodeId(),t.getAgentInstanceId(),t.getCommandId(),command.idempotencyKey(),t.getExecutionMode(),t.getRequestedBy(),timestamp(t.getUpdatedAt()),
                t.getAttempts(),t.getResultCode(),command.targetAddress(),command.agentNodeId(),command.storeId(),timestamp(command.expiresAt()),command.version(),
                t.getProjectId()==null?IdentityConstants.LEGACY_PROJECT_ID:t.getProjectId(),
                t.getActorUserId()==null?IdentityConstants.LEGACY_USER_ID:t.getActorUserId(),
                t.getAuthMode()==null?"LEGACY_TOKEN":t.getAuthMode());
            return;
        }
        jdbc.update(sql,
                t.getId(),t.getInstanceId(),t.getAction(),t.getStatus().name(),t.getMessage(),timestamp(t.getCreatedAt()),timestamp(t.getFinishedAt()),
                t.getNodeId(),t.getAgentInstanceId(),t.getCommandId(),command.idempotencyKey(),t.getExecutionMode(),t.getRequestedBy(),timestamp(t.getUpdatedAt()),
                t.getAttempts(),t.getResultCode(),command.targetAddress(),command.agentNodeId(),command.storeId(),timestamp(command.expiresAt()),command.version());
    }
    public boolean updateState(Task value, long expectedVersion, boolean accepted) {
        return jdbc.update("UPDATE tasks SET status=?,message=?,finished_at=?,updated_at=?,attempts=?,result_code=?,agent_accepted=CASE WHEN ? THEN TRUE ELSE agent_accepted END,version_no=version_no+1 WHERE id=? AND version_no=?",
                value.getStatus().name(),value.getMessage(),timestamp(value.getFinishedAt()),timestamp(value.getUpdatedAt()),value.getAttempts(),value.getResultCode(),accepted,value.getId(),expectedVersion)==1;
    }
    /** 兼容既有演示/仓储测试；没有队列，不会被当成真实命令下发。 */
    public Task save(Task value) {
        if (value.getCommandId()!=null) throw new IllegalArgumentException("durable commands require TaskQueueStore");
        value.setExecutionMode("LEGACY_MOCK");
        value.setResultCode("LEGACY_MOCK");
        if (value.getUpdatedAt()==null) value.setUpdatedAt(value.getFinishedAt()==null?value.getCreatedAt():value.getFinishedAt());
        jdbc.query("SELECT node_id,agent_instance_id FROM instances WHERE id=?",rs -> {
            value.setNodeId(rs.getString("node_id")); value.setAgentInstanceId(rs.getString("agent_instance_id"));
        },value.getInstanceId());
        if (findById(value.getId()).isEmpty()) insert(new TaskCommand(value,null,null,null,null,null,0,false));
        else jdbc.update("UPDATE tasks SET status=?,message=?,finished_at=?,updated_at=? WHERE id=? AND command_id IS NULL",
                value.getStatus().name(),value.getMessage(),timestamp(value.getFinishedAt()),timestamp(value.getUpdatedAt()),value.getId());
        return value;
    }
    public List<TaskEvent> events(String id) {
        return jdbc.query("SELECT * FROM task_events WHERE task_id=? ORDER BY event_sequence", (rs,row) ->
                new TaskEvent(rs.getString("id"),rs.getString("task_id"),rs.getLong("event_sequence"),
                        rs.getString("from_status")==null?null:TaskStatus.valueOf(rs.getString("from_status")),TaskStatus.valueOf(rs.getString("to_status")),
                        rs.getString("actor"),rs.getString("reason"),instant(rs,"occurred_at")), id);
    }
    public void appendEvent(String taskId,long sequence,TaskStatus from,TaskStatus to,String actor,String reason,Instant at) {
        jdbc.update("INSERT INTO task_events(id,task_id,event_sequence,from_status,to_status,actor,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),taskId,sequence,from==null?null:from.name(),to.name(),actor,reason,timestamp(at));
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM tasks",Long.class); }
    public boolean hasNodeHistory(String nodeId) { return jdbc.queryForObject("SELECT COUNT(*) FROM tasks t JOIN instances i ON i.id=t.instance_id WHERE i.node_id=?",Long.class,nodeId)>0; }
    private boolean hasColumn(String column) {
        try { return jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='TASKS' AND COLUMN_NAME=?",Integer.class,column.toUpperCase())>0; }
        catch (RuntimeException ignored) { return false; }
    }
}
