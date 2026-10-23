package com.argus.controlcenter.domain;

import java.time.Instant;

public class AiAgentRun {
    private String id, sessionId, projectId, actorUserId, question, status, phase, answer, evidenceJson, actionPlanJson, errorCode, taskId;
    private Instant createdAt, updatedAt;
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
    public String getErrorCode(){return errorCode;} public void setErrorCode(String v){errorCode=v;}
    public String getTaskId(){return taskId;} public void setTaskId(String v){taskId=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
    public Instant getUpdatedAt(){return updatedAt;} public void setUpdatedAt(Instant v){updatedAt=v;}
    public long getVersionNo(){return versionNo;} public void setVersionNo(long v){versionNo=v;}
}
