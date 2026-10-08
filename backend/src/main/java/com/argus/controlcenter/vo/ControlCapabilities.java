package com.argus.controlcenter.vo;
import java.util.List;
/** 浏览器据此展示服务端允许的目标和模式；创建时仍须再次验证。 */
public record ControlCapabilities(boolean controlEnabled,boolean canControl,List<String> allowedActions,
                                  List<Target> targets,String reason) {
    public record Target(String instanceId,String executionMode,List<String> allowedActions,String blockingTaskId,String blockedReason,boolean canConfirmPending) { }
}
