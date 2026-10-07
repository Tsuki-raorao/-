package com.argus.controlcenter.dto;

import jakarta.validation.constraints.NotBlank;

/** 实例操作请求。请求体示例：{"action":"START"}。 */
public class ActionRequest {
    /** 允许值由 InstanceService 统一校验：START、STOP、RESTART。 */
    @NotBlank private String action;
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
