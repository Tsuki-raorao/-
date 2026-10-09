package com.argus.controlcenter.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/** AI 生成的操作必须经过用户明确确认；当前只开放重启，避免把自然语言直接变成任意命令。 */
public class AiOperationRequest {
    @NotBlank private String instanceId;
    @NotBlank private String expectedExecutionMode;
    private String projectId;
    @AssertTrue(message = "必须明确确认这次重启")
    private boolean confirm;

    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { instanceId = value; }
    public String getExpectedExecutionMode() { return expectedExecutionMode; }
    public void setExpectedExecutionMode(String value) { expectedExecutionMode = value; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String value) { projectId = value; }
    public boolean isConfirm() { return confirm; }
    public void setConfirm(boolean value) { confirm = value; }
}
