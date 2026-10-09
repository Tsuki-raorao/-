package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.*;
import com.argus.controlcenter.repository.TaskResolutionRepository;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.argus.controlcenter.identity.*;

@Service
public class TaskReviewService {
    public record Capabilities(boolean reviewEnabled,boolean canReview,boolean canRecheck,List<String> allowedDecisions,
                               String reason,boolean blocksInstance,String resolutionId) { }
    private final TaskReviewStore store;private final TaskResolutionRepository repository;private final TaskReviewPolicy policy;
    private final TaskReviewGateway gateway;private final ObjectMapper json;
    private final CurrentActorProvider actorProvider;
    private final ProjectAuthorization authorization;
    public TaskReviewService(TaskReviewStore store,TaskResolutionRepository repository,TaskReviewPolicy policy,TaskReviewGateway gateway,ObjectMapper json) {
        this(store,repository,policy,gateway,json,null,null);
    }
    @Autowired
    public TaskReviewService(TaskReviewStore store,TaskResolutionRepository repository,TaskReviewPolicy policy,TaskReviewGateway gateway,ObjectMapper json,
                             CurrentActorProvider actorProvider,ProjectAuthorization authorization) {
        this.store=store;this.repository=repository;this.policy=policy;this.gateway=gateway;
        this.actorProvider=actorProvider;this.authorization=authorization;
        this.json=json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }
    public TaskResolution submit(String taskId,String key,String raw,boolean operator) {
        return submit(taskId,key,raw,operator,null,actorProvider==null?new CurrentActor(IdentityConstants.LEGACY_USER_ID,AuthMode.LEGACY_TOKEN,PlatformRole.SYSTEM_LEGACY,true):actorProvider.current());
    }
    public TaskResolution submit(String taskId,String key,String raw,boolean operator,String projectId,CurrentActor actor) {
        if(raw==null||raw.length()>32768)throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");
        ReviewRequest request;
        try{request=ReviewRequest.parse(json.readTree(raw));}
        catch(java.io.IOException error){throw new IllegalArgumentException("INVALID_REVIEW_REQUEST");}
        return store.submit(taskId,key,request,operator,projectId,actor);
    }
    public TaskResolution find(String id){return repository.view(repository.find(id).orElseThrow(()->new NotFoundException("resolution not found")));}
    public TaskResolution findScoped(String id,String projectId,CurrentActor actor){
        authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ); TaskResolution value=find(id);
        if(!projectId.equals(value.projectId()))throw new NotFoundException("resolution not found"); return value;
    }
    public TaskResolution byTask(String id){store.task(id);return repository.byTask(id).map(repository::view).orElse(null);}
    public TaskResolution byTaskScoped(String id,String projectId,CurrentActor actor){
        authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ); TaskResolution value=byTask(id);
        if(value!=null&&!projectId.equals(value.projectId())) throw new NotFoundException("resolution not found"); return value;
    }
    public TaskResolution byRequestKeyScoped(String key,String projectId,CurrentActor actor) {
        if (authorization.isIdentityMode()) authorization.require(actor,projectId,ProjectPermission.RESOURCE_READ);
        var row = authorization.isIdentityMode() ? repository.byKey(projectId,actor.userId(),key) : repository.byKey(key);
        return row.map(repository::view).orElseThrow(()->new NotFoundException("REQUEST_NOT_FOUND"));
    }
    public List<TaskResolution.Event> events(String id){find(id);return repository.events(id);}
    public Capabilities capabilities(String id,boolean operator) {
        return capabilities(id,operator,null,actorProvider==null?null:actorProvider.current());
    }
    public Capabilities capabilities(String id,boolean operator,String projectId,CurrentActor actor) {
        if(authorization!=null&&authorization.isIdentityMode()) authorization.require(actor,projectId,ProjectPermission.TASK_REVIEW);
        TaskCommand task=store.task(id);var prior=repository.byTask(id);
        String denial=policy.denial(operator,task.task().getInstanceId());
        if(denial==null)denial=store.eligibility(task,false);
        boolean ready=denial==null;
        boolean create=ready&&prior.isEmpty(),recheck=ready&&prior.map(r->r.status().equals("BLOCKED")).orElse(false);
        String reason=denial!=null?denial:prior.map(r->r.status().equals("APPLIED")?"REVIEW_ALREADY_APPLIED":r.status().equals("BLOCKED")?"READY":"REVIEW_IN_PROGRESS").orElse("READY");
        return new Capabilities(policy.enabled(),create,recheck,create||recheck?List.of(ReviewRequest.DECISION):List.of(),reason,store.ownsInstance(task),prior.map(TaskResolutionRepository.Row::id).orElse(null));
    }
    /** 只读即时证据；未知值保持null，不用缺失布尔值假称执行器已停止。 */
    public JsonNode evidence(String id) { return evidence(id,null,actorProvider==null?null:actorProvider.current()); }
    public JsonNode evidence(String id,String projectId,CurrentActor actor) {
        if(authorization!=null&&authorization.isIdentityMode()) authorization.require(actor,projectId,ProjectPermission.TASK_REVIEW);
        TaskCommand original=store.task(id);Task t=original.task();ObjectNode out=json.createObjectNode();
        out.put("taskId",t.getId());out.put("commandId",t.getCommandId());out.put("instanceId",t.getInstanceId());out.put("nodeId",t.getNodeId());out.put("agentInstanceId",t.getAgentInstanceId());out.put("checkedAt",Instant.now().toString());
        for(String key:List.of("bindingMatches","agentTask","workerActive","knownProcessesActive","lockDisposition","processAssessment"))out.putNull(key);
        out.put("canAcknowledge",false);
        String denial=store.eligibility(original,false);
        if(denial!=null){if(denial.equals("TARGET_CHANGED"))out.put("bindingMatches",false);return classified(out,denial.equals("TARGET_CHANGED")?"BINDING_CHANGED":"INELIGIBLE",denial);}
        if(!policy.configured())return classified(out,"REVIEW_DISABLED","REVIEW_UNCONFIGURED");
        try {
            gateway.health(original);out.put("bindingMatches",true);
            var response=gateway.evidence(original);
            if(response.status()==404)return classified(out,"RECORD_MISSING","AGENT_RECORD_LOST");
            if(response.status()!=200)return classified(out,"UNAVAILABLE","AGENT_EVIDENCE_UNAVAILABLE");
            gateway.validateEvidence(original,response.body());JsonNode body=response.body();
            out.set("agentTask",body.get("task"));
            for(String key:List.of("workerActive","knownProcessesActive","lockDisposition","processAssessment"))out.set(key,body.get(key));
            String blocked=gateway.evidenceBlock(body);
            if(blocked!=null)return classified(out,blocked.equals("REVIEW_EXECUTOR_ACTIVE")?"EXECUTOR_ACTIVE":blocked.equals("AGENT_REVIEW_DISABLED")?"REVIEW_DISABLED":"INELIGIBLE",blocked);
            out.put("canAcknowledge",true);return classified(out,"READY","READY");
        }catch(TaskControlException failure) {
            String code=failure.getMessage();
            if(code.equals("AGENT_BINDING_CHANGED")||code.equals("AGENT_TASK_IDENTITY_MISMATCH")){out.put("bindingMatches",false);return classified(out,"BINDING_CHANGED",code);}
            if(Set.of("REVIEW_EVIDENCE_INVALID","AGENT_TASK_RESPONSE_INVALID","REVIEW_PROTOCOL_UNSUPPORTED","AGENT_TASK_PROTOCOL_INVALID").contains(code))return classified(out,"INVALID_EVIDENCE",code);
            return classified(out,"UNAVAILABLE",code);
        }
    }
    private ObjectNode classified(ObjectNode node,String classification,String reason){node.put("classification",classification);node.put("reason",reason);return node;}
}
