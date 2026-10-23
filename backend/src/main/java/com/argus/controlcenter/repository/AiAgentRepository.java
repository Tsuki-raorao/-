package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.AiAgentRun;
import com.argus.controlcenter.domain.AiAgentSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
public class AiAgentRepository {
    private final JdbcTemplate jdbc;
    public AiAgentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public AiAgentSession createSession(String project,String actor,String title){
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        jdbc.update("INSERT INTO ai_sessions(id,project_id,actor_user_id,title,status,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,0)",id,project,actor,title==null||title.isBlank()?"未命名运维会话":title,"ACTIVE",Timestamp.from(now),Timestamp.from(now));
        return new AiAgentSession(id,project,actor,title==null||title.isBlank()?"未命名运维会话":title,"ACTIVE",now,now,0);
    }
    public Optional<AiAgentSession> session(String id,String project,String actor){
        return jdbc.query("SELECT * FROM ai_sessions WHERE id=? AND project_id=? AND actor_user_id=?",(rs,n)->new AiAgentSession(rs.getString("id"),rs.getString("project_id"),rs.getString("actor_user_id"),rs.getString("title"),rs.getString("status"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant(),rs.getLong("version_no")),id,project,actor).stream().findFirst();
    }
    public AiAgentRun createRun(String session,String project,String actor,String question){
        String id=UUID.randomUUID().toString(); Instant now=Instant.now();
        jdbc.update("INSERT INTO ai_agent_runs(id,session_id,project_id,actor_user_id,question,status,phase,evidence_json,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?,?,?,0)",id,session,project,actor,question,"RUNNING","CONTEXT", "[]",Timestamp.from(now),Timestamp.from(now));
        return getRun(id,project,actor).orElseThrow();
    }
    public Optional<AiAgentRun> getRun(String id,String project,String actor){
        return jdbc.query("SELECT * FROM ai_agent_runs WHERE id=? AND project_id=? AND actor_user_id=?",this::mapRun,id,project,actor).stream().findFirst();
    }
    public List<Map<String,Object>> steps(String runId){
        return jdbc.queryForList("SELECT id,sequence_no,phase,status,input_json,output_json,error_code,started_at,finished_at FROM ai_agent_steps WHERE run_id=? ORDER BY sequence_no",runId);
    }
    public void updateRun(AiAgentRun r){
        jdbc.update("UPDATE ai_agent_runs SET status=?,phase=?,answer=?,evidence_json=?,action_plan_json=?,error_code=?,task_id=?,updated_at=?,version_no=version_no+1 WHERE id=? AND project_id=? AND actor_user_id=?",r.getStatus(),r.getPhase(),r.getAnswer(),r.getEvidenceJson(),r.getActionPlanJson(),r.getErrorCode(),r.getTaskId(),Timestamp.from(Instant.now()),r.getId(),r.getProjectId(),r.getActorUserId());
    }
    public void step(String run,int seq,String phase,String status,String input,String output,String error){
        Instant now=Instant.now(); jdbc.update("INSERT INTO ai_agent_steps(id,run_id,sequence_no,phase,status,input_json,output_json,error_code,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID().toString(),run,seq,phase,status,input,output,error,Timestamp.from(now),Timestamp.from(now));
    }
    public int nextStepSequence(String runId){
        Integer next=jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0)+1 FROM ai_agent_steps WHERE run_id=?",Integer.class,runId);
        return next==null?1:next;
    }
    /** 将进程崩溃后遗留的 RUNNING 运行标记为可恢复，避免永久占用运行配额。 */
    public int recoverStaleRuns(Instant cutoff){
        List<String> ids=jdbc.query("SELECT id FROM ai_agent_runs WHERE status='RUNNING' AND updated_at<?",
                (rs,n)->rs.getString("id"),Timestamp.from(cutoff));
        int recovered=0;
        for(String id:ids){
            int updated=jdbc.update("UPDATE ai_agent_runs SET status='RECOVERY_REQUIRED',phase='RECOVERY',error_code='AI_RUN_INTERRUPTED',updated_at=?,version_no=version_no+1 WHERE id=? AND status='RUNNING'",
                    Timestamp.from(Instant.now()),id);
            if(updated==1){
                step(id,nextStepSequence(id),"RECOVERY","RECOVERY_REQUIRED","scheduler",null,"AI_RUN_INTERRUPTED");
                recovered++;
            }
        }
        return recovered;
    }
    private AiAgentRun mapRun(java.sql.ResultSet rs,int n)throws java.sql.SQLException{AiAgentRun r=new AiAgentRun();r.setId(rs.getString("id"));r.setSessionId(rs.getString("session_id"));r.setProjectId(rs.getString("project_id"));r.setActorUserId(rs.getString("actor_user_id"));r.setQuestion(rs.getString("question"));r.setStatus(rs.getString("status"));r.setPhase(rs.getString("phase"));r.setAnswer(rs.getString("answer"));r.setEvidenceJson(rs.getString("evidence_json"));r.setActionPlanJson(rs.getString("action_plan_json"));r.setErrorCode(rs.getString("error_code"));r.setTaskId(rs.getString("task_id"));r.setCreatedAt(rs.getTimestamp("created_at").toInstant());r.setUpdatedAt(rs.getTimestamp("updated_at").toInstant());r.setVersionNo(rs.getLong("version_no"));return r;}
}
