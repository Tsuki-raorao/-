package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.*;
import com.argus.controlcenter.repository.*;
import com.argus.controlcenter.vo.ControlCapabilities;
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
    public TaskService(TaskRepository repository,InstanceService instances,NodeRepository nodes,TaskQueueStore store,TaskControlPolicy policy,AgentCommandGateway gateway) {
        this.repository=repository;this.instances=instances;this.nodes=nodes;this.store=store;this.policy=policy;this.gateway=gateway;
    }
    public List<Task> findAll() { return repository.findAll(); }
    public Task findById(String id) { return repository.findById(id).orElseThrow(()->new NotFoundException("task not found")); }
    public List<TaskEvent> events(String id) { findById(id);return repository.events(id); }
    public ControlCapabilities capabilities(boolean operator) {
        boolean enabled=policy.enabled();
        List<ControlCapabilities.Target> targets=instances.findAll().stream()
                .filter(instance->Set.of("MOCK","DOCKER").contains(instance.getDataSource()))
                .map(instance->new ControlCapabilities.Target(instance.getId(),instance.getDataSource(),
                        operator&&policy.targetAllowed(instance.getId(),instance.getDataSource())?TaskControlPolicy.ACTIONS:List.<String>of())).toList();
        boolean can=operator&&enabled&&targets.stream().anyMatch(value->!value.allowedActions().isEmpty());
        return new ControlCapabilities(enabled,can,can?TaskControlPolicy.ACTIONS:List.of(),targets,
                !enabled?"CONTROL_DISABLED":!operator?"OPERATOR_REQUIRED":!can?"NO_ALLOWED_TARGETS":"READY");
    }
    /** 网络健康读取在事务外；接受后只入持久队列，绝不写实例观测状态。 */
    public Task execute(String instanceId,String action,String mode,String key,boolean operator) {
        policy.requireOperator(operator);
        if(action==null || !TaskControlPolicy.ACTIONS.contains(action)) throw new IllegalArgumentException("action must be START, STOP or RESTART");
        if(!Set.of("MOCK","DOCKER").contains(mode==null?"":mode)) throw new IllegalArgumentException("expectedExecutionMode must be MOCK or DOCKER");
        try {
            if(key==null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException();
        } catch(IllegalArgumentException e) { throw new IllegalArgumentException("Idempotency-Key must be a canonical UUID"); }
        Optional<TaskCommand> prior=repository.findByKey(key);
        if(prior.isPresent()) return TaskQueueStore.same(prior.get(),instanceId,action,mode);
        if(!policy.enabled()) throw new TaskControlException(403,"CONTROL_DISABLED");
        policy.requireTarget(instanceId,mode);
        Instance instance=instances.findById(instanceId);
        if(!mode.equals(instance.getDataSource())) throw new TaskControlException(409,"EXECUTION_MODE_CHANGED");
        Node node=nodes.findById(instance.getNodeId()).orElseThrow(()->new NotFoundException("node not found"));
        String address=gateway.normalizeAddress(node.getAddress());
        AgentCommandGateway.Health health=gateway.health(address);
        if(!health.executionMode().equals(mode)) throw new TaskControlException(409,"EXECUTION_MODE_CHANGED");
        if(!health.controlEnabled()) throw new TaskControlException(403,"AGENT_CONTROL_DISABLED");
        return store.enqueue(instance,address,action,mode,key,health);
    }
}
