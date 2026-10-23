package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.repository.AiAgentRepository;
import com.argus.controlcenter.config.ApiAccessInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** 项目级 Agent 编排：持久化会话和步骤，模型只负责分析，所有事实来自受控读取工具。 */
@Service
public class AiAgentService {
    private final AiAgentRepository repository; private final ProjectAuthorization authorization;
    private final NodeService nodes; private final InstanceService instances; private final LogService logs; private final TaskService tasks;
    private final AiProperties properties; private final ObjectMapper mapper;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AiAgentService(AiAgentRepository repository,ProjectAuthorization authorization,NodeService nodes,InstanceService instances,LogService logs,TaskService tasks,AiProperties properties,ObjectMapper mapper){this.repository=repository;this.authorization=authorization;this.nodes=nodes;this.instances=instances;this.logs=logs;this.tasks=tasks;this.properties=properties;this.mapper=mapper;}
    public AiAgentSession createSession(String projectId,String title,CurrentActor actor){String p=scope(projectId);authorization.require(actor,p,ProjectPermission.RESOURCE_READ);return repository.createSession(p,actor.userId(),title);}
    public AiAgentRun run(String sessionId,String projectId,String question,CurrentActor actor){
        String p=scope(projectId); authorization.require(actor,p,ProjectPermission.RESOURCE_READ);
        AiAgentSession session=repository.session(sessionId,p,actor.userId()).orElseThrow(()->new IllegalArgumentException("AI_SESSION_NOT_FOUND"));
        AiAgentRun run=repository.createRun(session.id(),p,actor.userId(),question);
        try {
            String context=context(p,actor); repository.step(run.getId(),1,"CONTEXT","SUCCEEDED",mapper.writeValueAsString(Map.of("projectId",p,"question",question)),context,null);
            run.setPhase("ANALYSIS"); repository.updateRun(run);
            String answer=askModel(question,context); repository.step(run.getId(),2,"ANALYSIS","SUCCEEDED",context,answer,null);
            run.setAnswer(answer); run.setEvidenceJson(evidence(p)); run.setActionPlanJson(actionPlan(answer)); run.setPhase("PROPOSAL"); run.setStatus("WAITING_APPROVAL"); repository.updateRun(run);
            repository.step(run.getId(),3,"PROPOSAL","WAITING_APPROVAL",answer,run.getActionPlanJson(),null);
        } catch(Exception e){run.setStatus("FAILED");run.setPhase("FAILED");run.setErrorCode(e instanceof IllegalStateException?e.getMessage():"AI_AGENT_FAILED");repository.updateRun(run);repository.step(run.getId(),2,run.getPhase(),"FAILED",question,null,run.getErrorCode());throw new IllegalStateException(run.getErrorCode(),e);}
        return repository.getRun(run.getId(),p,actor.userId()).orElse(run);
    }
    public AiAgentRun get(String id,String projectId,CurrentActor actor){String p=scope(projectId);authorization.require(actor,p,ProjectPermission.RESOURCE_READ);return repository.getRun(id,p,actor.userId()).orElseThrow(()->new IllegalArgumentException("AI_RUN_NOT_FOUND"));}
    public java.util.List<java.util.Map<String,Object>> steps(String id,String projectId,CurrentActor actor){String p=scope(projectId);authorization.require(actor,p,ProjectPermission.RESOURCE_READ);repository.getRun(id,p,actor.userId()).orElseThrow(()->new IllegalArgumentException("AI_RUN_NOT_FOUND"));return repository.steps(id);}
    public AiAgentRun approve(String id,String projectId,String instanceId,String mode,String key,CurrentActor actor,boolean legacyOperator){
        String p=scope(projectId); authorization.require(actor,p,ProjectPermission.TASK_OPERATE);
        AiAgentRun run=repository.getRun(id,p,actor.userId()).orElseThrow(()->new IllegalArgumentException("AI_RUN_NOT_FOUND"));
        if(!"WAITING_APPROVAL".equals(run.getStatus())) throw new IllegalStateException("AI_RUN_NOT_APPROVABLE");
        if(instanceId==null||instanceId.isBlank()||mode==null||mode.isBlank()) throw new IllegalArgumentException("AI_APPROVAL_TARGET_REQUIRED");
        Task submitted;
        try { submitted=tasks.execute(instanceId,"RESTART",mode,key,p,actor,legacyOperator); }
        catch(RuntimeException e){run.setErrorCode(e.getMessage());repository.updateRun(run);repository.step(id,4,"APPROVAL","REJECTED",instanceId,null,e.getMessage());throw e;}
        run.setTaskId(submitted.getId());run.setStatus("EXECUTION_SUBMITTED");run.setPhase("EXECUTION");repository.updateRun(run);repository.step(id,4,"APPROVAL","APPROVED",instanceId,submitted.getId(),null);return repository.getRun(id,p,actor.userId()).orElse(run);
    }
    public AiAgentRun verify(String id,String projectId,CurrentActor actor){
        String p=scope(projectId); authorization.require(actor,p,ProjectPermission.RESOURCE_READ);
        AiAgentRun run=repository.getRun(id,p,actor.userId()).orElseThrow(()->new IllegalArgumentException("AI_RUN_NOT_FOUND"));
        if(run.getTaskId()==null||run.getTaskId().isBlank()) throw new IllegalStateException("AI_RUN_TASK_NOT_BOUND");
        Task task=tasks.findById(run.getTaskId(),p,actor);
        if(task.getStatus()==TaskStatus.SUCCEEDED){run.setStatus("COMPLETED");run.setPhase("VERIFICATION");}
        else if(task.getStatus()==TaskStatus.FAILED||task.getStatus()==TaskStatus.UNKNOWN){run.setStatus("EXECUTION_FAILED");run.setPhase("VERIFICATION");run.setErrorCode(task.getResultCode());}
        else {run.setStatus("VERIFICATION_PENDING");run.setPhase("VERIFICATION");}
        repository.updateRun(run);repository.step(id,5,"VERIFICATION",run.getStatus(),task.getId(),task.getStatus().name(),run.getErrorCode());
        return repository.getRun(id,p,actor.userId()).orElse(run);
    }
    private String scope(String p){if(p==null||p.isBlank()){if(authorization.isIdentityMode())throw new IdentityAuthorizationException("PROJECT_REQUIRED",400);return IdentityConstants.LEGACY_PROJECT_ID;}return p;}
    private String context(String p,CurrentActor actor)throws Exception{var out=mapper.createObjectNode();var ns=out.putArray("nodes");nodes.findAll().stream().filter(n->p.equals(n.getProjectId())).forEach(n->{var x=ns.addObject();x.put("id",n.getId());x.put("name",n.getName());x.put("status",String.valueOf(n.getStatus()));x.put("sampledAt",String.valueOf(n.getSampledAt()));});var is=out.putArray("instances");instances.findAll().stream().filter(i->p.equals(i.getProjectId())).forEach(i->{var x=is.addObject();x.put("id",i.getId());x.put("name",i.getName());x.put("nodeId",i.getNodeId());x.put("status",String.valueOf(i.getStatus()));x.put("source",i.getDataSource());});out.put("logCount",logs.find(null,100,p).size());out.put("taskCount",tasks.findAll(p,actor).size());return mapper.writeValueAsString(out);}
    private String evidence(String p)throws Exception{return mapper.writeValueAsString(List.of(Map.of("type","PROJECT_SNAPSHOT","source","central.read_tools","projectId",p,"freshness","FRESH"),Map.of("type","TASK_HISTORY","source","central.tasks","projectId",p,"freshness","FRESH")));}
    private String askModel(String question,String context)throws Exception{if(!properties.isEnabled()||properties.getApiKey()==null||properties.getApiKey().isBlank())throw new IllegalStateException("AI_PROVIDER_NOT_CONFIGURED");var payload=mapper.createObjectNode();payload.put("model",properties.getModel());payload.put("max_tokens",properties.getMaxTokens());var messages=payload.putArray("messages");messages.addObject().put("role","system").put("content","你是Argus项目级运维Agent。只使用给出的项目证据，输出诊断结论、未知项、风险和一个待审批的ActionPlan。不要声称执行过任何操作，不要输出shell命令。");messages.addObject().put("role","user").put("content",question+"\n项目证据："+context);var req=HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/chat/completions")).timeout(properties.getTimeout()).header("Authorization","Bearer "+properties.getApiKey()).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();var response=client.send(req,HttpResponse.BodyHandlers.ofString());if(response.statusCode()/100!=2)throw new IllegalStateException("AI_PROVIDER_HTTP_"+response.statusCode());JsonNode root=mapper.readTree(response.body());String answer=root.path("choices").path(0).path("message").path("content").asText("");if(answer.isBlank())throw new IllegalStateException("AI_EMPTY_RESPONSE");return answer;}
    private String actionPlan(String answer)throws Exception{return mapper.writeValueAsString(Map.of("status","PROPOSAL_ONLY","action","RESTART","requiresApproval",true,"source","agent-analysis","summary","仅在用户确认且权限、实例和 Agent 状态再次通过检查后执行"));}
}
