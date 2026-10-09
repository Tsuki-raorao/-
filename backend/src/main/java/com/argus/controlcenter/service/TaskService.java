package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.*;
import com.argus.controlcenter.repository.*;
import com.argus.controlcenter.vo.ControlCapabilities;
import com.argus.controlcenter.identity.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class TaskService {
    private final TaskRepository repository;
    private final InstanceService instances;
    private final NodeRepository nodes;
    private final TaskQueueStore store;
    private final TaskControlPolicy policy;
    private final AgentCommandGateway gateway;
    private final TaskResolutionRepository resolutions;
    private final ProjectAuthorization authorization;
    private final CurrentActorProvider actorProvider;
    @Autowired
    public TaskService(TaskRepository repository,InstanceService instances,NodeRepository nodes,TaskQueueStore store,TaskControlPolicy policy,AgentCommandGateway gateway,TaskResolutionRepository resolutions,
                       ProjectAuthorization authorization,CurrentActorProvider actorProvider) {
        this.repository=repository;this.instances=instances;this.nodes=nodes;this.store=store;this.policy=policy;this.gateway=gateway;this.resolutions=resolutions;
        this.authorization=authorization;this.actorProvider=actorProvider;
    }
    public List<Task> findAll() { return resolutions.decorate(repository.findAll()); }
    public List<Task> findAll(String projectId,CurrentActor actor) { authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ); return resolutions.decorate(repository.findAll(projectId)); }
    public Task findById(String id) { return decorate(repository.findById(id).orElseThrow(()->new NotFoundException("task not found"))); }
    public Task findById(String id,String projectId,CurrentActor actor) {
        authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ); Task task=findById(id);
        if(!projectId.equals(task.getProjectId())) throw new NotFoundException("task not found"); return task;
    }
    public Task byRequestKey(String key,String projectId,CurrentActor actor) {
        if (authorization.isIdentityMode()) {
            authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ);
            return repository.findByKey(projectId,actor.userId(),key).map(TaskCommand::task).map(this::decorate)
                    .orElseThrow(()->new NotFoundException("REQUEST_NOT_FOUND"));
        }
        return repository.findByKey(key).map(TaskCommand::task).map(this::decorate)
                .orElseThrow(()->new NotFoundException("REQUEST_NOT_FOUND"));
    }
    private Task decorate(Task task){return resolutions.decorate(List.of(task)).get(0);}
    public List<TaskEvent> events(String id) { findById(id);return repository.events(id); }
    public ControlCapabilities capabilities(boolean operator) {
        boolean enabled=policy.enabled();
        Map<String,String> locks=resolutions.blockingTasks();
        List<ControlCapabilities.Target> targets=instances.findAll().stream()
                .filter(instance->Set.of("MOCK","DOCKER").contains(instance.getDataSource()))
                .map(instance->new ControlCapabilities.Target(instance.getId(),instance.getDataSource(),
                        operator&&!locks.containsKey(instance.getId())&&policy.targetAllowed(instance.getId(),instance.getDataSource())?TaskControlPolicy.ACTIONS:List.<String>of(),
                        locks.get(instance.getId()),locks.containsKey(instance.getId())?"INSTANCE_HAS_UNRESOLVED_TASK":null,
                        operator&&policy.targetAllowed(instance.getId(),instance.getDataSource()))).toList();
        boolean can=operator&&enabled&&targets.stream().anyMatch(ControlCapabilities.Target::canConfirmPending);
        return new ControlCapabilities(enabled,can,can?TaskControlPolicy.ACTIONS:List.of(),targets,
                !enabled?"CONTROL_DISABLED":!operator?"OPERATOR_REQUIRED":!can?"NO_ALLOWED_TARGETS":"READY");
    }
    /** 身份模式能力只返回当前项目的目标，并把操作权限作为服务端事实。 */
    public ControlCapabilities capabilities(String projectId, CurrentActor actor) {
        authorization.require(actor, projectId, ProjectPermission.RESOURCE_READ);
        boolean canOperate = false;
        try { authorization.require(actor, projectId, ProjectPermission.TASK_OPERATE); canOperate = true; }
        catch (IdentityAuthorizationException ignored) { }
        final boolean operatorAllowed = canOperate;
        Map<String,String> allLocks=resolutions.blockingTasks();
        List<ControlCapabilities.Target> targets=instances.findAll().stream()
                .filter(instance->projectId.equals(instance.getProjectId()))
                .filter(instance->Set.of("MOCK","DOCKER").contains(instance.getDataSource()))
                .map(instance->new ControlCapabilities.Target(instance.getId(),instance.getDataSource(),
                        operatorAllowed&&policy.enabled()&&!allLocks.containsKey(instance.getId())&&policy.targetAllowed(instance.getId(),instance.getDataSource())?TaskControlPolicy.ACTIONS:List.<String>of(),
                        allLocks.get(instance.getId()),allLocks.containsKey(instance.getId())?"INSTANCE_HAS_UNRESOLVED_TASK":null,
                        operatorAllowed&&policy.targetAllowed(instance.getId(),instance.getDataSource()))).toList();
        boolean can=operatorAllowed&&policy.enabled()&&targets.stream().anyMatch(ControlCapabilities.Target::canConfirmPending);
        return new ControlCapabilities(policy.enabled(),can,can?TaskControlPolicy.ACTIONS:List.of(),targets,
                !policy.enabled()?"CONTROL_DISABLED":!operatorAllowed?"OPERATOR_REQUIRED":!can?"NO_ALLOWED_TARGETS":"READY");
    }
    /** 网络健康读取在事务外；接受后只入持久队列，绝不写实例观测状态。 */
    public Task execute(String instanceId,String action,String mode,String key,boolean operator) {
        // 保留旧测试/遗留适配入口；身份模式下 actorProvider 会重新从安全上下文获取主体。
        CurrentActor actor = actorProvider.current();
        return execute(instanceId,action,mode,key,null,actor,operator);
    }

    public Task execute(String instanceId,String action,String mode,String key,String projectId,CurrentActor actor,boolean legacyOperator) {
        boolean identityMode=authorization.isIdentityMode();
        if(identityMode && (projectId==null||projectId.isBlank())) throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);
        if(!identityMode) policy.requireOperator(legacyOperator);
        if(identityMode) authorization.require(actor,projectId,ProjectPermission.TASK_OPERATE);
        if(action==null || !TaskControlPolicy.ACTIONS.contains(action)) throw new IllegalArgumentException("action must be START, STOP or RESTART");
        if(!Set.of("MOCK","DOCKER").contains(mode==null?"":mode)) throw new IllegalArgumentException("expectedExecutionMode must be MOCK or DOCKER");
        try {
            if(key==null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Idempotency-Key must be a canonical UUID"); }
        // 先按资源授权；身份模式的同键查找必须带 project/actor，避免跨项目泄漏。
        Optional<TaskCommand> prior=authorization.isIdentityMode()
                ? repository.findByKey(projectId,actor.userId(),key)
                : repository.findByKey(key);
        if(prior.isPresent()) return decorate(TaskQueueStore.same(prior.get(),instanceId,action,mode));
        if(!policy.enabled()) throw new TaskControlException(403,"CONTROL_DISABLED");
        policy.requireTarget(instanceId,mode);
        Instance instance=instances.findById(instanceId);
        if(!mode.equals(instance.getDataSource())) throw new TaskControlException(409,"EXECUTION_MODE_CHANGED");
        Node node=nodes.findById(instance.getNodeId()).orElseThrow(()->new NotFoundException("node not found"));
        String address=gateway.normalizeAddress(node.getAddress());
        AgentCommandGateway.Health health=gateway.health(address);
        if(!health.executionMode().equals(mode)) throw new TaskControlException(409,"EXECUTION_MODE_CHANGED");
        if(!health.controlEnabled()) throw new TaskControlException(403,"AGENT_CONTROL_DISABLED");
        return decorate(store.enqueue(instance,address,action,mode,key,health,projectId,actor));
    }
}
