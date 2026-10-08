package com.argus.controlcenter.dto;

import jakarta.validation.constraints.NotBlank;

/** 实例操作请求：必须同时声明动作与用户确认的执行模式。 */
public class ActionRequest {
    /** 允许值由 TaskControlPolicy 统一定义：START、STOP、RESTART。 */
    @NotBlank private String action;
    @NotBlank private String expectedExecutionMode;
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getExpectedExecutionMode() { return expectedExecutionMode; }
    public void setExpectedExecutionMode(String value) { expectedExecutionMode = value; }
}
