package com.argus.controlcenter.service;

import com.argus.controlcenter.config.TaskControlProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.*;
import com.argus.controlcenter.repository.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 所有数据库状态转换均为短事务；此类绝不执行 HTTP。 */
@Service
public class TaskQueueStore {
    public record Claim(TaskCommand command,String owner) { }
    private final JdbcTemplate jdbc;
    private final TaskRepository repository;
    private final NodeRepository nodes;
    private final InstanceRepository instances;
    private final TaskControlPolicy policy;
    private final AgentCommandGateway gateway;
    private final TaskControlProperties properties;
    private final TransactionTemplate tx;
    public TaskQueueStore(JdbcTemplate jdbc,TaskRepository repository,NodeRepository nodes,InstanceRepository instances,
                          TaskControlPolicy policy,AgentCommandGateway gateway,TaskControlProperties properties,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.repository=repository;this.nodes=nodes;this.instances=instances;this.policy=policy;this.gateway=gateway;this.properties=properties;
        tx=new TransactionTemplate(manager);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }
    private static java.sql.Timestamp timestamp(Instant value) { return value==null?null:java.sql.Timestamp.from(value); }
    public Task enqueue(Instance expected,String address,String action,String mode,String key,AgentCommandGateway.Health health) {
        try {
            return tx.execute(ignored -> {
                Node node=nodes.findByIdForUpdate(expected.getNodeId()).orElseThrow(()->new NotFoundException("node not found"));
                Instance instance=instances.findByIdForUpdate(expected.getId()).orElseThrow(()->new NotFoundException("instance not found"));
                Optional<TaskCommand> prior=repository.findByKey(key);
                if(prior.isPresent()) return same(prior.get(),instance.getId(),action,mode);
                policy.requireTarget(instance.getId(),mode);
                if(!instance.getNodeId().equals(expected.getNodeId()) || !instance.getAgentInstanceId().equals(expected.getAgentInstanceId())
                        || !gateway.normalizeAddress(node.getAddress()).equals(address) || !mode.equals(instance.getDataSource()))
                    throw new TaskControlException(409,"TARGET_CHANGED");
                if(jdbc.queryForObject("SELECT COUNT(*) FROM instance_task_locks WHERE instance_id=?",Long.class,instance.getId())>0)
                    throw new TaskControlException(409,"INSTANCE_HAS_UNRESOLVED_TASK");
                Instant time=now();
                Task task=new Task(UUID.randomUUID().toString(),instance.getId(),action,TaskStatus.PENDING,"ACCEPTED",time,null);
                task.setNodeId(instance.getNodeId());task.setAgentInstanceId(instance.getAgentInstanceId());task.setCommandId(UUID.randomUUID().toString());
                task.setExecutionMode(mode);task.setRequestedBy("operator");task.setUpdatedAt(time);task.setResultCode("ACCEPTED");
                repository.insert(new TaskCommand(task,key,address,health.nodeId(),health.storeId(),time.plus(properties.getCommandTtl()).truncatedTo(ChronoUnit.MICROS),1,false));
                jdbc.update("INSERT INTO task_queue(task_id,next_run_at) VALUES(?,?)",task.getId(),timestamp(time));
                jdbc.update("INSERT INTO instance_task_locks(instance_id,task_id) VALUES(?,?)",instance.getId(),task.getId());
                repository.appendEvent(task.getId(),1,null,TaskStatus.PENDING,"operator","ACCEPTED",time);
                return repository.findById(task.getId()).orElseThrow();
            });
        } catch(DuplicateKeyException e) {
            // 不在已失败事务中读取；并发同键获胜者已提交后返回它，否则保持实例互斥冲突。
            return repository.findByKey(key).map(value->same(value,expected.getId(),action,mode))
                    .orElseThrow(()->new TaskControlException(409,"INSTANCE_HAS_UNRESOLVED_TASK"));
        }
    }
    public static Task same(TaskCommand prior,String instanceId,String action,String mode) {
        Task task=prior.task();
        if(!task.getInstanceId().equals(instanceId)||!task.getAction().equals(action)||!task.getExecutionMode().equals(mode))
            throw new TaskControlException(409,"IDEMPOTENCY_CONFLICT");
        return task;
    }
    public Optional<Claim> claim() {
        return tx.execute(ignored -> {
            Instant time=now();
            List<String> candidates=jdbc.queryForList("SELECT task_id FROM task_queue WHERE next_run_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_run_at,task_id LIMIT 16",String.class,timestamp(time),timestamp(time));
            for(String id:candidates) {
                String owner=UUID.randomUUID().toString();
                if(jdbc.update("UPDATE task_queue SET lease_owner=?,lease_until=? WHERE task_id=? AND next_run_at<=? AND (lease_until IS NULL OR lease_until<=?)",
                        owner,timestamp(time.plus(policy.safeLease())),id,timestamp(time),timestamp(time))==1)
                    return Optional.of(new Claim(repository.findCommand(id).orElseThrow(),owner));
            }
            return Optional.empty();
        });
    }
    /** POST 前保存投递次数及状态；租约不再属于本 worker 时禁止发出请求。 */
    public boolean beginPost(Claim claim) {
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            if(!ownsLocked(claim)) return false;
            TaskCommand current=repository.lock(claim.command().task().getId());
            Task task=current.task();
            if(stopped(task.getStatus())) return false;
            transition(current,TaskStatus.DISPATCHING,"DISPATCHING",true,false);
            return true;
        }));
    }
    public boolean finish(Claim claim,TaskStatus status,String code) {
        return finish(claim,status,code,false);
    }
    public boolean finish(Claim claim,TaskStatus status,String code,boolean accepted) {
        return Boolean.TRUE.equals(tx.execute(ignored -> {
            if(!ownsLocked(claim)) return false;
            TaskCommand current=repository.lock(claim.command().task().getId());
            if(stopped(current.task().getStatus())) return false;
            transition(current,status,code,false,accepted);
            if(stopped(status)) {
                jdbc.update("DELETE FROM task_queue WHERE task_id=? AND lease_owner=?",current.task().getId(),claim.owner());
                // UNKNOWN 不释放互斥，等待单独的人工核对流程。
                if(status!=TaskStatus.UNKNOWN) jdbc.update("DELETE FROM instance_task_locks WHERE task_id=?",current.task().getId());
            } else {
                jdbc.update("UPDATE task_queue SET next_run_at=?,lease_owner=NULL,lease_until=NULL WHERE task_id=? AND lease_owner=?",
                        timestamp(now().plus(properties.getRetryDelay())),current.task().getId(),claim.owner());
            }
            return true;
        }));
    }
    private boolean ownsLocked(Claim claim) {
        List<Map<String,Object>> row=jdbc.queryForList("SELECT lease_owner,lease_until FROM task_queue WHERE task_id=? FOR UPDATE",claim.command().task().getId());
        if(row.isEmpty()) return false;
        return jdbc.queryForObject("SELECT COUNT(*) FROM task_queue WHERE task_id=? AND lease_owner=? AND lease_until>?",Long.class,
                claim.command().task().getId(),claim.owner(),timestamp(now()))==1;
    }
    private void transition(TaskCommand current,TaskStatus state,String code,boolean attempt,boolean accepted) {
        Task task=current.task();TaskStatus previous=task.getStatus();
        if(!attempt && (!accepted||current.agentAccepted()) && previous==state && Objects.equals(task.getResultCode(),code)) return;
        Instant time=now();
        task.setStatus(state);task.setResultCode(code);task.setMessage(code);task.setUpdatedAt(time);
        if(attempt) task.setAttempts(task.getAttempts()+1);
        if(stopped(state)) task.setFinishedAt(time);
        if(!repository.updateState(task,current.version(),accepted)) throw new IllegalStateException("task state CAS failed while locked");
        repository.appendEvent(task.getId(),current.version()+1,previous,state,"worker",code,time);
    }
    public static boolean stopped(TaskStatus state) { return state==TaskStatus.SUCCEEDED||state==TaskStatus.FAILED||state==TaskStatus.UNKNOWN; }
}
