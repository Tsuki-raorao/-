package com.argus.controlcenter.dto;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
public class ApproveAiRunRequest {
    @NotBlank private String instanceId;
    @NotBlank private String expectedExecutionMode;
    @AssertTrue(message="必须明确批准该方案") private boolean approve;
    public String getInstanceId(){return instanceId;} public void setInstanceId(String v){instanceId=v;}
    public String getExpectedExecutionMode(){return expectedExecutionMode;} public void setExpectedExecutionMode(String v){expectedExecutionMode=v;}
    public boolean isApprove(){return approve;} public void setApprove(boolean v){approve=v;}
}
