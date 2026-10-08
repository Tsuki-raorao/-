package com.argus.controlcenter.service;

import com.argus.controlcenter.config.TaskControlProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.TaskControlException;
import com.argus.controlcenter.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 可重启的数据库队列消费者；只有同一绑定下明确缺失的命令才允许原样投递。 */
@Service
public class TaskWorker {
    private final TaskQueueStore store;
    private final AgentCommandGateway gateway;
    private final TaskControlPolicy policy;
    private final TaskControlProperties properties;
    private final NodeRepository nodes;
    private final InstanceRepository instances;
    public TaskWorker(TaskQueueStore store,AgentCommandGateway gateway,TaskControlPolicy policy,TaskControlProperties properties,NodeRepository nodes,InstanceRepository instances) {
        this.store=store;this.gateway=gateway;this.policy=policy;this.properties=properties;this.nodes=nodes;this.instances=instances;
    }
    @Scheduled(initialDelayString="${argus.tasks.initial-delay-ms:5000}",fixedDelayString="${argus.tasks.poll-interval-ms:1000}")
    public void runOnce() { store.claim().ifPresent(this::process); }
    public void process(TaskQueueStore.Claim claim) {
        TaskCommand command=claim.command();
        try {
            Node node=nodes.findById(command.task().getNodeId()).orElse(null);
            Instance instance=instances.findById(command.task().getInstanceId()).orElse(null);
            if(node==null || instance==null || !instance.getNodeId().equals(command.task().getNodeId())
                    || !instance.getAgentInstanceId().equals(command.task().getAgentInstanceId())
                    || !gateway.normalizeAddress(node.getAddress()).equals(command.targetAddress())) {
                store.finish(claim,TaskStatus.UNKNOWN,"TARGET_CHANGED");return;
            }
            AgentCommandGateway.Health health=gateway.health(command.targetAddress());
            if(!health.nodeId().equals(command.agentNodeId()) || !health.storeId().equals(command.storeId()) || !health.executionMode().equals(command.task().getExecutionMode())) {
                store.finish(claim,TaskStatus.UNKNOWN,"AGENT_BINDING_CHANGED");return;
            }
            AgentCommandGateway.Response query=gateway.query(command);
            if(query.status()==200) { accept(claim,query);return; }
            if(query.status()!=404) { retry(claim,"AGENT_QUERY_UNCONFIRMED");return; }
            // 一旦确认 Agent 接收过，后续“消失”不能成为重启动作的重放依据。
            if(command.agentAccepted()) {
                store.finish(claim,TaskStatus.UNKNOWN,"AGENT_RECORD_LOST");return;
            }
            if(!TaskQueueStore.now().isBefore(command.expiresAt())) {
                store.finish(claim,TaskStatus.FAILED,"COMMAND_EXPIRED_ABSENT");return;
            }
            if(!health.controlEnabled() || !policy.targetAllowed(command.task().getInstanceId(),command.task().getExecutionMode())) {
                retry(claim,"CONTROL_DISABLED");return;
            }
            if(!command.task().getExecutionMode().equals(instance.getDataSource())) {
                store.finish(claim,TaskStatus.UNKNOWN,"EXECUTION_MODE_CHANGED");return;
            }
            if(!store.beginPost(claim)) return;
            AgentCommandGateway.Response response=gateway.post(command);
            if(response.status()==200 || response.status()==202) accept(claim,response);
            else if(response.status()==409) store.finish(claim,TaskStatus.UNKNOWN,"AGENT_COMMAND_CONFLICT");
            // 拒绝响应也不能证明此前迟到的同命令未被接收；下一轮仍先查询持久记录。
            else retry(claim,"AGENT_ACCEPTANCE_UNCONFIRMED");
        } catch(TaskControlException e) {
            if(e.getMessage().equals("AGENT_TASK_IDENTITY_MISMATCH") || e.getMessage().equals("AGENT_TASK_RESPONSE_INVALID")
                    || e.getMessage().equals("AGENT_TASK_PROTOCOL_INVALID") || e.getMessage().equals("INVALID_AGENT_ADDRESS"))
                store.finish(claim,TaskStatus.UNKNOWN,e.getMessage());
            else retry(claim,e.getMessage());
        }
    }
    private void accept(TaskQueueStore.Claim claim,AgentCommandGateway.Response response) {
        AgentCommandGateway.Reply reply=gateway.validate(claim.command(),response.body());
        if((reply.status()==TaskStatus.DELIVERED || reply.status()==TaskStatus.RUNNING)
                && TaskQueueStore.now().isAfter(claim.command().expiresAt().plus(properties.getConfirmationGrace()))) {
            store.finish(claim,TaskStatus.UNKNOWN,"CONFIRMATION_EXPIRED");return;
        }
        store.finish(claim,reply.status(),reply.resultCode(),true);
    }
    private void retry(TaskQueueStore.Claim claim,String code) {
        if(TaskQueueStore.now().isAfter(claim.command().expiresAt().plus(properties.getConfirmationGrace())))
            store.finish(claim,TaskStatus.UNKNOWN,"CONFIRMATION_EXPIRED");
        else store.finish(claim,TaskStatus.RETRY_WAIT,code);
    }
}
