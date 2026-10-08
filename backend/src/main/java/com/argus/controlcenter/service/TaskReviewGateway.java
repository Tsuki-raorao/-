package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.TaskControlException;
import com.argus.controlcenter.repository.TaskResolutionRepository.Row;
import com.fasterxml.jackson.databind.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** 固定核对路径，复用完整HTTP正文截止；没有任何原动作POST方法。 */
@Service
public class TaskReviewGateway {
    private final AgentCommandGateway transport;
    private final ObjectMapper json;
    private static final Set<String> TASK_FIELDS=Set.of("taskId","instanceId","action","status","message","createdAt","startedAt","finishedAt","executionMode","nodeId","storeId","resultCode","observedStatus");
    public TaskReviewGateway(AgentCommandGateway transport,ObjectMapper json){this.transport=transport;this.json=json;}
    public void health(TaskCommand original) {
        var r=transport.exchange(original.targetAddress(),"/api/agent/health",null,false);
        if(r.status()!=200)throw error("AGENT_HEALTH_UNAVAILABLE");
        JsonNode h=r.body();
        if(!"1.0".equals(text(h,"reviewProtocolVersion"))||!h.path("reviewEnabled").isBoolean())throw error("REVIEW_PROTOCOL_UNSUPPORTED");
        binding(original,h);
        // 已关闭时仍须允许查询已写回执；新核对POST由实际Agent与中央开关守护。
    }
    public AgentCommandGateway.Response query(Row row) {
        return transport.exchange(row.original().targetAddress(),path(row.original())+"/resolutions/"+row.id(),null,true);
    }
    public AgentCommandGateway.Response task(TaskCommand original){return transport.query(original);}
    public AgentCommandGateway.Response evidence(TaskCommand original) {
        return transport.exchange(original.targetAddress(),path(original)+"/review-evidence",null,true);
    }
    public AgentCommandGateway.Response post(Row row) {
        try{return transport.exchange(row.original().targetAddress(),path(row.original())+"/resolutions",json.writeValueAsString(ResolutionHash.payload(row.id(),row.original(),row.request())),true);}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException(e);}
    }
    private String path(TaskCommand original){return "/api/agent/tasks/"+original.task().getCommandId();}
    public void validateTask(TaskCommand original,JsonNode task) {
        fields(task,TASK_FIELDS);transport.validate(original,task);
    }
    public void validateEvidence(TaskCommand original,JsonNode body) {
        fields(body,Set.of("commandId","nodeId","storeId","executionMode","checkedAt","task","reviewEnabled","instanceAllowed","workerActive","knownProcessesActive","lockDisposition","processAssessment"));
        if(!original.task().getCommandId().equals(text(body,"commandId")))throw error("REVIEW_EVIDENCE_INVALID");
        binding(original,body);validateTask(original,body.path("task"));instant(body,"checkedAt");
        for(String f:List.of("reviewEnabled","instanceAllowed","workerActive","knownProcessesActive"))if(!body.path(f).isBoolean())throw error("REVIEW_EVIDENCE_INVALID");
        if(!Set.of("OWNED_BY_COMMAND","NONE","OWNED_BY_OTHER").contains(text(body,"lockDisposition")))throw error("REVIEW_EVIDENCE_INVALID");
        process(body);
    }
    public String evidenceBlock(JsonNode body) {
        if(!body.path("reviewEnabled").booleanValue())return "AGENT_REVIEW_DISABLED";
        if(!body.path("instanceAllowed").booleanValue())return "AGENT_REVIEW_TARGET_NOT_ALLOWED";
        String status=text(body.path("task"),"status");
        if(Set.of("PENDING","RUNNING").contains(status)||body.path("workerActive").booleanValue()||body.path("knownProcessesActive").booleanValue())return "REVIEW_EXECUTOR_ACTIVE";
        String lock=text(body,"lockDisposition");
        if(lock.equals("OWNED_BY_OTHER")||status.equals("UNKNOWN")&&!lock.equals("OWNED_BY_COMMAND"))return "AGENT_REVIEW_LOCK_MISMATCH";
        return null;
    }
    public JsonNode validateReceipt(Row row,JsonNode receipt) {
        fields(receipt,Set.of("resolutionId","commandId","instanceId","action","nodeId","storeId","executionMode","decision","status","requestHash","createdAt","classification","task","processAssessment","observationStatus","observedInstanceStatus","observedAt"));
        TaskCommand original=row.original();Task task=original.task();
        if(!row.id().equals(text(receipt,"resolutionId"))||!task.getCommandId().equals(text(receipt,"commandId"))
                ||!task.getAgentInstanceId().equals(text(receipt,"instanceId"))||!task.getAction().toLowerCase(Locale.ROOT).equals(text(receipt,"action"))
                ||!row.request().decision().equals(text(receipt,"decision"))||!"CLOSED".equals(text(receipt,"status"))||!row.requestHash().equals(text(receipt,"requestHash")))throw error("REVIEW_RECEIPT_INVALID");
        binding(original,receipt);validateTask(original,receipt.path("task"));process(receipt);
        String classification=text(receipt,"classification"),state=text(receipt.path("task"),"status");
        if(!(classification.equals("ACKNOWLEDGED_UNKNOWN")&&state.equals("UNKNOWN")
                ||classification.equals("CONFIRMED_TERMINAL")&&Set.of("SUCCEEDED","FAILED").contains(state)))throw error("REVIEW_RECEIPT_INVALID");
        Instant created=instant(receipt,"createdAt"),observed=instant(receipt,"observedAt"),finished=instant(receipt.path("task"),"finishedAt");
        if(created.isBefore(finished)||observed.isBefore(finished)||created.isBefore(instant(receipt.path("task"),"createdAt"))||observed.isAfter(created))throw error("REVIEW_RECEIPT_INVALID");
        String availability=text(receipt,"observationStatus");JsonNode value=receipt.get("observedInstanceStatus");
        if(value==null||!(availability.equals("UNAVAILABLE")&&value.isNull()
                ||availability.equals("AVAILABLE")&&value.isTextual()&&Set.of("RUNNING","STOPPED","UNKNOWN").contains(value.textValue())))throw error("REVIEW_RECEIPT_INVALID");
        return receipt.deepCopy();
    }
    private void process(JsonNode body) {
        if(!Set.of("CURRENT_PROCESS_CLEARED","PRIOR_PROCESS_UNVERIFIED").contains(text(body,"processAssessment")))throw error("REVIEW_EVIDENCE_INVALID");
    }
    private void binding(TaskCommand original,JsonNode body) {
        if(!original.agentNodeId().equals(text(body,"nodeId"))||!original.storeId().equals(text(body,"storeId"))||!original.task().getExecutionMode().equals(text(body,"executionMode")))throw error("AGENT_BINDING_CHANGED");
    }
    private void fields(JsonNode node,Set<String> allowed) {
        if(!node.isObject()||node.size()!=allowed.size())throw error("REVIEW_EVIDENCE_INVALID");
        node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw error("REVIEW_EVIDENCE_INVALID");});
    }
    private Instant instant(JsonNode node,String key) {
        try{return Instant.parse(text(node,key));}catch(RuntimeException e){throw error("REVIEW_EVIDENCE_INVALID");}
    }
    public static String text(JsonNode node,String name){return node.path(name).isTextual()?node.path(name).textValue():"";}
    private TaskControlException error(String code){return new TaskControlException(502,code);}
}
