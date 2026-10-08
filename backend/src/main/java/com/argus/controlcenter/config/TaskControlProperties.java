package com.argus.controlcenter.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 可靠任务的独立开关、目标授权及有界恢复窗口。默认不允许任何控制。 */
@ConfigurationProperties(prefix = "argus.tasks")
public class TaskControlProperties {
    private boolean controlEnabled;
    private boolean allowDocker;
    private Set<String> allowedInstanceIds = new LinkedHashSet<>();
    private String agentControlToken = "";
    private Duration lease = Duration.ofSeconds(45);
    private Duration retryDelay = Duration.ofSeconds(3);
    private Duration commandTtl = Duration.ofMinutes(5);
    private Duration confirmationGrace = Duration.ofMinutes(5);
    public boolean isControlEnabled() { return controlEnabled; }
    public void setControlEnabled(boolean value) { controlEnabled = value; }
    public boolean isAllowDocker() { return allowDocker; }
    public void setAllowDocker(boolean value) { allowDocker = value; }
    public Set<String> getAllowedInstanceIds() { return allowedInstanceIds; }
    public void setAllowedInstanceIds(Set<String> value) { allowedInstanceIds = value == null ? new LinkedHashSet<>() : new LinkedHashSet<>(value); }
    public String getAgentControlToken() { return agentControlToken; }
    public void setAgentControlToken(String value) { agentControlToken = value == null ? "" : value; }
    public Duration getLease() { return lease; }
    public void setLease(Duration value) { lease = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { retryDelay = value; }
    public Duration getCommandTtl() { return commandTtl; }
    public void setCommandTtl(Duration value) { commandTtl = value; }
    public Duration getConfirmationGrace() { return confirmationGrace; }
    public void setConfirmationGrace(Duration value) { confirmationGrace = value; }
}
