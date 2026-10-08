package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.exception.TaskControlException;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Service;

/** 每次提交和实际投递均重新判定；查看权限与控制权限独立。 */
@Service
public class TaskControlPolicy {
    public static final List<String> ACTIONS = List.of("START","STOP","RESTART");
    private final SecurityProperties security;
    private final AgentGatewayProperties gateway;
    private final TaskControlProperties tasks;
    public TaskControlPolicy(SecurityProperties security,AgentGatewayProperties gateway,TaskControlProperties tasks) {
        this.security=security; this.gateway=gateway; this.tasks=tasks;
        for (Duration value : List.of(tasks.getLease(),tasks.getRetryDelay(),tasks.getCommandTtl(),tasks.getConfirmationGrace())) {
            if (value.isZero()||value.isNegative()) throw new IllegalStateException("task durations must be positive");
        }
        if (tasks.isControlEnabled() && tasks.getLease().compareTo(gateway.getConnectTimeout().plus(gateway.getReadTimeout()).multipliedBy(3))<=0)
            throw new IllegalStateException("task lease must exceed three complete agent HTTP request budgets");
        if (tasks.isControlEnabled() && !configured()) throw new IllegalStateException("task control requires independent tokens, authenticated API and allowed agent gateway");
    }
    private boolean configured() {
        return security.isApiAuthRequired() && !security.getApiAccessToken().isBlank() && !security.getApiControlToken().isBlank()
                && !security.getApiAccessToken().equals(security.getApiControlToken())
                && gateway.isEnabled() && gateway.isReadOnly() && gateway.isAllowControl() && gateway.isRequireAllowlist() && !gateway.getAllowedHosts().isEmpty()
                && !gateway.getAuthToken().isBlank() && !tasks.getAgentControlToken().isBlank() && !tasks.getAgentControlToken().equals(gateway.getAuthToken());
    }
    public boolean enabled() { return tasks.isControlEnabled() && !security.isReadOnly() && configured(); }
    public Duration safeLease() {
        Duration budget=gateway.getConnectTimeout().plus(gateway.getReadTimeout()).multipliedBy(3).plusSeconds(1);
        return tasks.getLease().compareTo(budget)>0?tasks.getLease():budget;
    }
    public boolean targetAllowed(String id,String mode) {
        return enabled() && tasks.getAllowedInstanceIds().contains(id) && ("MOCK".equals(mode)||("DOCKER".equals(mode)&&tasks.isAllowDocker()));
    }
    public void requireOperator(boolean operator) {
        if (!operator) throw new TaskControlException(403,"OPERATOR_REQUIRED");
        if (security.isReadOnly()) throw new TaskControlException(405,"READ_ONLY");
    }
    public void requireTarget(String id,String mode) {
        if (!targetAllowed(id,mode)) throw new TaskControlException(403,"TARGET_NOT_ALLOWED");
    }
}
