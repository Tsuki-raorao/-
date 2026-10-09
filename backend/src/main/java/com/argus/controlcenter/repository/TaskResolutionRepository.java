package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.service.ResolutionHash;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.argus.controlcenter.repository.JdbcValues.*;
import com.argus.controlcenter.identity.IdentityConstants;
import com.argus.controlcenter.identity.IdentityAuthorizationException;

/** 不可变意图与原执行快照分开保存；后续只更新核对元数据和经过验证的回执。 */
@Repository
public class TaskResolutionRepository {
    public record Row(String id,String requestKey,TaskCommand original,ReviewRequest request,String requestHash,
                      String status,String resultCode,int attempts,int cycleAttempts,long version,
                      Instant createdAt,Instant updatedAt,Instant appliedAt,JsonNode receipt,String activeAuthorizationId) { }
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public TaskResolutionRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
    public String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("resolution encoding failed",e);}
    }
    private Row map(ResultSet rs,int index)throws SQLException {
        try {
            String receipt=rs.getString("receipt_json");
            if(receipt!=null&&!ResolutionHash.sha(receipt.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(rs.getString("receipt_hash")))
                throw new IllegalStateException("resolution receipt checksum mismatch");
            return new Row(rs.getString("id"),rs.getString("request_key"),json.readValue(rs.getString("binding_json"),TaskCommand.class),
                    json.readValue(rs.getString("intent_json"),ReviewRequest.class),rs.getString("request_hash"),rs.getString("status"),rs.getString("result_code"),
                    rs.getInt("attempts"),rs.getInt("cycle_attempts"),rs.getLong("version_no"),instant(rs,"created_at"),instant(rs,"updated_at"),instant(rs,"applied_at"),receipt==null?null:json.readTree(receipt),optional(rs,"active_authorization_id"));
        }catch(SQLException e){throw e;}catch(Exception e){throw new IllegalStateException("invalid persisted resolution",e);}
    }
    public Optional<Row> find(String id){return jdbc.query("SELECT * FROM task_resolutions WHERE id=?",this::map,id).stream().findFirst();}
    public Optional<Row> byKey(String key){return jdbc.query("SELECT * FROM task_resolutions WHERE request_key=?",this::map,key).stream().findFirst();}
    public Optional<Row> byKey(String projectId,String actorUserId,String key){
        if(!hasColumn("project_id")) return Optional.empty();
        return jdbc.query("SELECT * FROM task_resolutions WHERE project_id=? AND actor_user_id=? AND request_key=?",this::map,projectId,actorUserId,key).stream().findFirst();
    }
    public Optional<Row> byTask(String id){return jdbc.query("SELECT * FROM task_resolutions WHERE task_id=?",this::map,id).stream().findFirst();}
    public Row lock(String id){return jdbc.query("SELECT * FROM task_resolutions WHERE id=? FOR UPDATE",this::map,id).stream().findFirst().orElseThrow();}
    public void insert(String id,String key,TaskCommand original,ReviewRequest request,Instant now) {
        // V7 将归属字段设为 NOT NULL；旧测试/历史调用仍走这个重载，必须明确归入系统遗留主体。
        if (hasColumn("project_id")) {
            String projectId = original.task().getProjectId() == null ? IdentityConstants.LEGACY_PROJECT_ID : original.task().getProjectId();
            String actorId = original.task().getActorUserId() == null ? IdentityConstants.LEGACY_USER_ID : original.task().getActorUserId();
            String authMode = original.task().getAuthMode() == null ? "LEGACY_TOKEN" : original.task().getAuthMode();
            insert(id,key,original,request,now,projectId,actorId,authMode);
            return;
        }
        jdbc.update("INSERT INTO task_resolutions(id,task_id,request_key,binding_json,intent_json,request_hash,status,result_code,attempts,cycle_attempts,version_no,requested_by,created_at,updated_at) VALUES(?,?,?,?,?,?,'PENDING','REVIEW_ACCEPTED',0,0,1,'operator',?,?)",
                id,original.task().getId(),key,encode(original),encode(request),ResolutionHash.request(id,original,request),timestamp(now),timestamp(now));
        event(id,1,null,"PENDING","operator","REVIEW_ACCEPTED",now);
    }
    /** 身份模式写入；旧库/旧测试仍走上面的兼容入口。 */
    public void insert(String id,String key,TaskCommand original,ReviewRequest request,Instant now,String projectId,String actorUserId,String authMode) {
        if(!hasColumn("project_id")){ insert(id,key,original,request,now); return; }
        projectId = projectId == null || projectId.isBlank() ? IdentityConstants.LEGACY_PROJECT_ID : projectId;
        actorUserId = actorUserId == null || actorUserId.isBlank() ? IdentityConstants.LEGACY_USER_ID : actorUserId;
        authMode = authMode == null || authMode.isBlank() ? "LEGACY_TOKEN" : authMode;
        jdbc.update("INSERT INTO task_resolutions(id,task_id,request_key,binding_json,intent_json,request_hash,status,result_code,attempts,cycle_attempts,version_no,requested_by,created_at,updated_at,project_id,actor_user_id,auth_mode) VALUES(?,?,?,?,?,?,'PENDING','REVIEW_ACCEPTED',0,0,1,?,?,?,?,?,?)",
                id,original.task().getId(),key,encode(original),encode(request),ResolutionHash.request(id,original,request),"operator",timestamp(now),timestamp(now),projectId,actorUserId,authMode);
        event(id,1,null,"PENDING",actorUserId,"REVIEW_ACCEPTED",now);
    }
    public boolean transition(Row row,String status,String code,String actor,int attempts,int cycleAttempts,JsonNode receipt,Instant now) {
        String raw=receipt==null?null:encode(receipt);
        int changed=jdbc.update("UPDATE task_resolutions SET status=?,result_code=?,attempts=?,cycle_attempts=?,version_no=version_no+1,updated_at=?,applied_at=?,receipt_json=COALESCE(?,receipt_json),receipt_hash=COALESCE(?,receipt_hash) WHERE id=? AND version_no=?",
                status,code,attempts,cycleAttempts,timestamp(now),status.equals("APPLIED")?timestamp(now):null,raw,
                raw==null?null:ResolutionHash.sha(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)),row.id(),row.version());
        if(changed==1)event(row.id(),row.version()+1,row.status(),status,actor,code,now);
        return changed==1;
    }
    public void activateAuthorization(String resolutionId,String authorizationId) {
        if (!hasColumn("active_authorization_id")) throw new IdentityAuthorizationException("IDENTITY_NOT_MIGRATED",500);
        jdbc.update("UPDATE task_resolutions SET active_authorization_id=? WHERE id=?",authorizationId,resolutionId);
    }
    public void authorizationEvent(String resolutionId,long sequence,String actor,String reason,Instant at) {
        if (!hasColumn("active_authorization_id")) return;
        jdbc.update("INSERT INTO task_resolution_events(id,resolution_id,event_sequence,from_status,to_status,actor,reason,occurred_at,actor_user_id) VALUES(?,?,?,?,?,?,?, ?,?)",
                UUID.randomUUID().toString(),resolutionId,sequence,"BLOCKED","PENDING","user",reason,timestamp(at),actor);
    }
    private void event(String id,long sequence,String from,String to,String actor,String reason,Instant at) {
        // 旧 actor 列只有 32 个字符；V7 另增 actor_user_id 保存完整主体 ID。
        if (hasColumn("actor_user_id")) {
            String actorUserId = actor != null && actor.length() == 36 ? actor : null;
            String display = actorUserId == null ? actor : "user";
            jdbc.update("INSERT INTO task_resolution_events(id,resolution_id,event_sequence,from_status,to_status,actor,reason,occurred_at,actor_user_id) VALUES(?,?,?,?,?,?,?, ?,?)",
                    UUID.randomUUID().toString(),id,sequence,from,to,display,reason,timestamp(at),actorUserId);
            return;
        }
        String display = actor != null && actor.length() > 32 ? "operator" : actor;
        jdbc.update("INSERT INTO task_resolution_events(id,resolution_id,event_sequence,from_status,to_status,actor,reason,occurred_at) VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),id,sequence,from,to,display,reason,timestamp(at));
    }
    public List<TaskResolution.Event> events(String id) {
        return jdbc.query("SELECT * FROM task_resolution_events WHERE resolution_id=? ORDER BY event_sequence",(rs,n)->new TaskResolution.Event(rs.getString("id"),id,rs.getLong("event_sequence"),rs.getString("from_status"),rs.getString("to_status"),rs.getString("actor"),rs.getString("reason"),instant(rs,"occurred_at")),id);
    }
    public TaskResolution view(Row row) {
        Task t=row.original().task();ReviewRequest r=row.request();JsonNode evidence=null;
        if(row.receipt()!=null) {
            JsonNode receipt=row.receipt(),task=receipt.path("task");ObjectNode node=json.createObjectNode();
            for(String key:List.of("classification","processAssessment","observationStatus","observedInstanceStatus","observedAt"))node.set(key,receipt.path(key));
            node.set("reviewedAt",receipt.path("createdAt"));node.set("taskStatus",task.path("status"));node.set("taskResultCode",task.path("resultCode"));
            node.set("taskObservedStatus",task.path("observedStatus"));node.set("taskFinishedAt",task.path("finishedAt"));evidence=node;
        }
        return new TaskResolution(row.id(),row.requestKey(),t.getId(),t.getInstanceId(),t.getNodeId(),t.getAgentInstanceId(),t.getCommandId(),r.decision(),row.status(),r.reason(),r.evidence(),r.acknowledgeNoReplay(),r.acknowledgeResidualRisk(),"operator",row.createdAt(),row.updatedAt(),row.appliedAt(),row.resultCode(),row.attempts(),evidence,
                t.getProjectId(),t.getActorUserId(),t.getAuthMode(),row.requestHash(),row.activeAuthorizationId(),null);
    }
    public Map<String,String> blockingTasks() {
        Map<String,String> locks=new HashMap<>();jdbc.query("SELECT instance_id,task_id FROM instance_task_locks",rs->{locks.put(rs.getString(1),rs.getString(2));});return locks;
    }
    private boolean hasColumn(String column){
        try{return jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='TASK_RESOLUTIONS' AND COLUMN_NAME=?",Integer.class,column.toUpperCase())>0;}
        catch(RuntimeException ignored){return false;}
    }
    private static String optional(ResultSet rs,String column) {
        try { return rs.getString(column); } catch (SQLException ignored) { return null; }
    }
    public List<Task> decorate(List<Task> tasks) {
        if(tasks.isEmpty())return tasks;
        Map<String,String> locks=blockingTasks();
        List<String> ids=tasks.stream().map(Task::getId).toList();Map<String,TaskResolution.Summary> reviews=new HashMap<>();
        String marks=String.join(",",Collections.nCopies(ids.size(),"?"));
        jdbc.query("SELECT id,task_id,status,result_code,applied_at FROM task_resolutions WHERE task_id IN ("+marks+")",rs->{
            reviews.put(rs.getString("task_id"),new TaskResolution.Summary(rs.getString("id"),rs.getString("status"),rs.getString("result_code"),instant(rs,"applied_at")));},ids.toArray());
        for(Task task:tasks){task.setBlocksInstance(task.getId().equals(locks.get(task.getInstanceId())));task.setReviewSummary(reviews.get(task.getId()));}
        return tasks;
    }
}
