package com.argus.controlcenter.service;

import com.argus.controlcenter.config.TaskReviewProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.*;
import com.argus.controlcenter.repository.*;
import com.argus.controlcenter.repository.TaskResolutionRepository.Row;
import com.fasterxml.jackson.databind.JsonNode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** 人工意图/outbox同事务，网络永不进入本类；只精确解除原任务持有的锁。 */
@Service
public class TaskReviewStore {
    public record Claim(Row row,String owner) { }
    private final JdbcTemplate jdbc;private final TaskResolutionRepository resolutions;private final TaskRepository tasks;
    private final NodeRepository nodes;private final InstanceRepository instances;private final AgentCommandGateway addresses;
    private final TaskReviewPolicy policy;private final TaskReviewProperties properties;private final TransactionTemplate tx;
    public TaskReviewStore(JdbcTemplate jdbc,TaskResolutionRepository resolutions,TaskRepository tasks,NodeRepository nodes,
            InstanceRepository instances,AgentCommandGateway addresses,TaskReviewPolicy policy,TaskReviewProperties properties,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.resolutions=resolutions;this.tasks=tasks;this.nodes=nodes;this.instances=instances;this.addresses=addresses;this.policy=policy;this.properties=properties;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    private Instant now(){return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(6)",Timestamp.class).toInstant();}
    private Timestamp stamp(Instant value){return Timestamp.from(value);}
    public TaskCommand task(String id){return tasks.findCommand(id).orElseThrow(()->new NotFoundException("task not found"));}
    public boolean ownsInstance(TaskCommand original) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks WHERE instance_id=? AND task_id=?",Long.class,original.task().getInstanceId(),original.task().getId())==1;
    }
    public String eligibility(TaskCommand original,boolean lockRows) {
        Task t=original.task();
        if(t.getStatus()!=TaskStatus.UNKNOWN||t.getCommandId()==null||original.storeId()==null||original.agentNodeId()==null||original.targetAddress()==null||original.expiresAt()==null||!Set.of("MOCK","DOCKER").contains(t.getExecutionMode()))return "TASK_NOT_REVIEWABLE";
        Node node=(lockRows?nodes.findByIdForUpdate(t.getNodeId()):nodes.findById(t.getNodeId())).orElse(null);
        Instance instance=(lockRows?instances.findByIdForUpdate(t.getInstanceId()):instances.findById(t.getInstanceId())).orElse(null);
        if(node==null||instance==null||!instance.getNodeId().equals(t.getNodeId())||!instance.getAgentInstanceId().equals(t.getAgentInstanceId()))return "TARGET_CHANGED";
        try{if(!addresses.normalizeAddress(node.getAddress()).equals(original.targetAddress()))return "TARGET_CHANGED";}
        catch(TaskControlException invalid){return "TARGET_CHANGED";}
        TaskCommand current=lockRows?tasks.lock(t.getId()):task(t.getId());
        if(current.task().getStatus()!=TaskStatus.UNKNOWN||current.version()!=original.version()||!sameBinding(current,original))return "TASK_CHANGED";
        if(!ownsInstance(original))return "REVIEW_LOCK_MISMATCH";
        if(jdbc.queryForObject("SELECT COUNT(*) FROM task_queue WHERE task_id=?",Long.class,t.getId())!=0)return "ORIGINAL_TASK_STILL_QUEUED";
        return null;
    }
    private boolean sameBinding(TaskCommand a,TaskCommand b) {
        Task x=a.task(),y=b.task();
        return Objects.equals(a.targetAddress(),b.targetAddress())&&Objects.equals(a.agentNodeId(),b.agentNodeId())&&Objects.equals(a.storeId(),b.storeId())&&Objects.equals(a.expiresAt(),b.expiresAt())
            &&Objects.equals(x.getCommandId(),y.getCommandId())&&Objects.equals(x.getInstanceId(),y.getInstanceId())&&Objects.equals(x.getNodeId(),y.getNodeId())&&Objects.equals(x.getAgentInstanceId(),y.getAgentInstanceId())
            &&Objects.equals(x.getAction(),y.getAction())&&Objects.equals(x.getExecutionMode(),y.getExecutionMode())&&Objects.equals(x.getResultCode(),y.getResultCode())&&Objects.equals(x.getMessage(),y.getMessage())
            &&Objects.equals(x.getFinishedAt(),y.getFinishedAt())&&Objects.equals(x.getUpdatedAt(),y.getUpdatedAt())&&x.getAttempts()==y.getAttempts()&&a.agentAccepted()==b.agentAccepted();
    }
    public TaskResolution submit(String taskId,String key,ReviewRequest request,boolean operator) {
        try{if(key==null||!UUID.fromString(key).toString().equals(key))throw new IllegalArgumentException();}
        catch(IllegalArgumentException e){throw new IllegalArgumentException("Idempotency-Key must be a canonical UUID");}
        TaskCommand original=task(taskId);policy.require(operator,original.task().getInstanceId());
        try {
            return tx.execute(ignored->{
                // 与新动作/节点维护采用同样的节点→实例顺序，再取得原任务。
                nodes.findByIdForUpdate(original.task().getNodeId()).orElseThrow(()->new NotFoundException("node not found"));
                instances.findByIdForUpdate(original.task().getInstanceId()).orElseThrow(()->new NotFoundException("instance not found"));
                TaskCommand current=tasks.lock(taskId);policy.require(operator,current.task().getInstanceId());
                Optional<Row> prior=resolutions.byKey(key);
                if(prior.isPresent()) {
                    Row row=resolutions.lock(prior.get().id());same(row,taskId,request);
                    if(row.status().equals("BLOCKED")) {
                        String denial=eligibility(row.original(),false);if(denial!=null)throw new TaskControlException(409,denial);
                        Instant time=now();
                        change(row,"PENDING","REVIEW_RECHECK_REQUESTED","operator",row.attempts(),0,null,time);
                        jdbc.update("INSERT INTO task_resolution_queue(resolution_id,next_run_at) VALUES(?,?)",row.id(),stamp(time));
                        row=resolutions.find(row.id()).orElseThrow();
                    }
                    return resolutions.view(row);
                }
                if(resolutions.byTask(taskId).isPresent())throw new TaskControlException(409,"TASK_ALREADY_HAS_RESOLUTION");
                String denial=eligibility(current,false);if(denial!=null)throw new TaskControlException(409,denial);
                String id=UUID.randomUUID().toString();Instant time=now();
                resolutions.insert(id,key,current,request,time);
                jdbc.update("INSERT INTO task_resolution_queue(resolution_id,next_run_at) VALUES(?,?)",id,stamp(time));
                return resolutions.view(resolutions.find(id).orElseThrow());
            });
        }catch(DuplicateKeyException collision) {
            policy.require(operator,original.task().getInstanceId());
            Row prior=resolutions.byKey(key).orElseThrow(()->new TaskControlException(409,"TASK_ALREADY_HAS_RESOLUTION"));same(prior,taskId,request);return resolutions.view(prior);
        }
    }
    private void same(Row row,String taskId,ReviewRequest request) {
        if(!row.original().task().getId().equals(taskId)||!row.request().equals(request))throw new TaskControlException(409,"REVIEW_IDEMPOTENCY_CONFLICT");
    }
    public Optional<Claim> claim() {
        if(!policy.enabled())return Optional.empty();
        return tx.execute(ignored->{
            Instant time=now();
            for(String id:jdbc.queryForList("SELECT resolution_id FROM task_resolution_queue WHERE next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_run_at,resolution_id LIMIT 16",String.class,stamp(time),stamp(time))) {
                String owner=UUID.randomUUID().toString();
                if(jdbc.update("UPDATE task_resolution_queue SET lease_owner=?,lease_until=? WHERE resolution_id=? AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?)",owner,stamp(time.plus(policy.safeLease())),id,stamp(time),stamp(time))!=1)continue;
                Row row=resolutions.lock(id);
                if(Set.of("BLOCKED","APPLIED").contains(row.status()))throw new IllegalStateException("stopped resolution remains queued");
                change(row,"PROCESSING","REVIEW_CHECKING","review-worker",row.attempts()+1,row.cycleAttempts()+1,null,time);
                return Optional.of(new Claim(resolutions.find(id).orElseThrow(),owner));
            }
            return Optional.empty();
        });
    }
    private boolean owns(Claim claim) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT lease_owner,lease_until FROM task_resolution_queue WHERE resolution_id=? FOR UPDATE",claim.row().id());
        return !rows.isEmpty()&&jdbc.queryForObject("SELECT COUNT(*) FROM task_resolution_queue WHERE resolution_id=? AND lease_owner=? AND lease_until>?",Long.class,claim.row().id(),claim.owner(),stamp(now()))==1;
    }
    public boolean beforePost(Claim claim) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            if(!owns(claim))return false;
            String denial=eligibility(claim.row().original(),true);
            Row row=resolutions.lock(claim.row().id());
            if(denial==null)denial=policy.denial(true,row.original().task().getInstanceId());
            if(denial!=null){blockLocked(row,denial,now());return false;}
            return true;
        }));
    }
    public boolean apply(Claim claim,JsonNode receipt) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            if(!owns(claim))return false;
            String denial=eligibility(claim.row().original(),true);
            Row row=resolutions.lock(claim.row().id());
            if(denial==null)denial=policy.denial(true,row.original().task().getInstanceId());
            if(denial!=null){blockLocked(row,denial,now());return false;}
            if(row.version()!=claim.row().version())return false;
            Task task=row.original().task();Instant time=now();
            if(jdbc.update("DELETE FROM instance_task_locks WHERE instance_id=? AND task_id=?",task.getInstanceId(),task.getId())!=1)throw new IllegalStateException("review lock owner changed while locked");
            change(row,"APPLIED","REVIEW_APPLIED","review-worker",row.attempts(),row.cycleAttempts(),receipt,time);
            jdbc.update("DELETE FROM task_resolution_queue WHERE resolution_id=? AND lease_owner=?",row.id(),claim.owner());return true;
        }));
    }
    public boolean failure(Claim claim,String code,boolean retry) {
        return Boolean.TRUE.equals(tx.execute(ignored->{
            if(!owns(claim))return false;
            Row row=resolutions.lock(claim.row().id());Instant time=now();
            if(!retry||row.cycleAttempts()>=properties.getMaxAttempts()){blockLocked(row,code,time);return true;}
            change(row,"RETRY_WAIT",code,"review-worker",row.attempts(),row.cycleAttempts(),null,time);
            jdbc.update("UPDATE task_resolution_queue SET next_run_at=?,lease_owner=NULL,lease_until=NULL WHERE resolution_id=? AND lease_owner=?",stamp(time.plus(properties.getRetryDelay())),row.id(),claim.owner());return true;
        }));
    }
    private void blockLocked(Row row,String code,Instant time) {
        change(row,"BLOCKED",code,"review-worker",row.attempts(),row.cycleAttempts(),null,time);
        jdbc.update("DELETE FROM task_resolution_queue WHERE resolution_id=?",row.id());
    }
    private void change(Row row,String state,String code,String actor,int attempts,int cycle,JsonNode receipt,Instant time) {
        if(!resolutions.transition(row,state,code,actor,attempts,cycle,receipt,time))throw new IllegalStateException("resolution CAS failed while locked");
    }
}
