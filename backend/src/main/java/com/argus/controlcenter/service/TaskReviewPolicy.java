package com.argus.controlcenter.service;

import com.argus.controlcenter.config.*;
import com.argus.controlcenter.exception.TaskControlException;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** 核对只写审计和解除旧互斥；绝不借此打开动作开关。 */
@Service
public class TaskReviewPolicy {
    private final SecurityProperties security;
    private final AgentGatewayProperties gateway;
    private final TaskControlProperties tasks;
    private final TaskReviewProperties review;
    public TaskReviewPolicy(SecurityProperties security,AgentGatewayProperties gateway,TaskControlProperties tasks,TaskReviewProperties review) {
        this.security=security;this.gateway=gateway;this.tasks=tasks;this.review=review;
        if(review.getLease().isNegative()||review.getLease().isZero()||review.getRetryDelay().isNegative()||review.getRetryDelay().isZero()||review.getMaxAttempts()<1||review.getMaxAttempts()>100)
            throw new IllegalStateException("invalid task review limits");
        if(review.isEnabled()&&(!configured()||review.getLease().compareTo(budget())<=0))
            throw new IllegalStateException("task review requires independent tokens, target allowlist and a bounded lease");
    }
    private Duration budget(){return gateway.getConnectTimeout().plus(gateway.getReadTimeout()).multipliedBy(4).plusSeconds(5);}
    public Duration safeLease(){return review.getLease().compareTo(budget())>0?review.getLease():budget().plusSeconds(1);}
    public boolean configured() {
        return security.isApiAuthRequired()&&!security.getApiAccessToken().isBlank()&&!security.getApiControlToken().isBlank()&&!security.getApiControlToken().equals(security.getApiAccessToken())
            &&gateway.isEnabled()&&gateway.isReadOnly()&&gateway.isRequireAllowlist()&&!gateway.getAllowedHosts().isEmpty()&&!gateway.getAuthToken().isBlank()
            &&!tasks.getAgentControlToken().isBlank()&&!tasks.getAgentControlToken().equals(gateway.getAuthToken())&&!review.getAllowedInstanceIds().isEmpty();
    }
    public boolean enabled(){return review.isEnabled()&&!security.isReadOnly()&&configured();}
    public String denial(boolean operator,String instanceId) {
        if(security.isReadOnly())return "READ_ONLY";
        if(!operator)return "OPERATOR_REQUIRED";
        if(!enabled())return "REVIEW_DISABLED";
        if(!review.getAllowedInstanceIds().contains(instanceId))return "REVIEW_TARGET_NOT_ALLOWED";
        return null;
    }
    public void require(boolean operator,String instanceId) {
        String reason=denial(operator,instanceId);if(reason!=null)throw new TaskControlException(reason.equals("READ_ONLY")?405:403,reason);
    }
}
