package com.argus.controlcenter.domain;

import java.time.Instant;

public class AiAgentRun {
    private String id, sessionId, projectId, actorUserId, question, status, phase, answer, evidenceJson, actionPlanJson, verificationJson, errorCode, taskId, retryOfRunId, retryKey, retryReason;
    private Instant createdAt, updatedAt, startedAt, finishedAt;
    private Long providerLatencyMs, providerCostMicros;
    private Integer promptChars, responseChars;
    private Integer providerPromptTokens, providerCompletionTokens, providerTotalTokens;
    private String providerModel;
    private long versionNo;
    public String getId(){return id;} public void setId(String v){id=v;}
    public String getSessionId(){return sessionId;} public void setSessionId(String v){sessionId=v;}
    public String getProjectId(){return projectId;} public void setProjectId(String v){projectId=v;}
    public String getActorUserId(){return actorUserId;} public void setActorUserId(String v){actorUserId=v;}
    public String getQuestion(){return question;} public void setQuestion(String v){question=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getPhase(){return phase;} public void setPhase(String v){phase=v;}
    public String getAnswer(){return answer;} public void setAnswer(String v){answer=v;}
    public String getEvidenceJson(){return evidenceJson;} public void setEvidenceJson(String v){evidenceJson=v;}
    public String getActionPlanJson(){return actionPlanJson;} public void setActionPlanJson(String v){actionPlanJson=v;}
    public String getVerificationJson(){return verificationJson;} public void setVerificationJson(String v){verificationJson=v;}
    public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;}
    public String getRetryOfRunId(){return retryOfRunId;} public void setRetryOfRunId(String v){retryOfRunId=v;}
    public String getRetryKey(){return retryKey;} public void setRetryKey(String v){retryKey=v;}
    public String getRetryReason(){return retryReason;} public void setRetryReason(String v){retryReason=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;}
    public Instant getFinishedAt(){return finishedAt;} public void setFinishedAt(Instant v){finishedAt=v;}
    public Long getProviderLatencyMs(){return providerLatencyMs;} public void setProviderLatencyMs(Long v){providerLatencyMs=v;}
    public Integer getProviderPromptTokens(){return providerPromptTokens;} public void setProviderPromptTokens(Integer v){providerPromptTokens=v;}
    public Integer getProviderCompletionTokens(){return providerCompletionTokens;} public void setProviderCompletionTokens(Integer v){providerCompletionTokens=v;}
    public Integer getProviderTotalTokens(){return providerTotalTokens;} public void setProviderTotalTokens(Integer v){providerTotalTokens=v;}
    public Long getProviderCostMicros(){return providerCostMicros;} public void setProviderCostMicros(Long v){providerCostMicros=v;}
    public Integer getPromptChars(){return promptChars;} public void setPromptChars(Integer v){promptChars=v;}
    public Integer getResponseChars(){return responseChars;} public void setResponseChars(Integer v){responseChars=v;}
    public String getProviderModel(){return providerModel;} public void setProviderModel(String v){providerModel=v;}
    public long getVersionNo(){return versionNo;} public void setVersionNo(long v){versionNo=v;}
}
