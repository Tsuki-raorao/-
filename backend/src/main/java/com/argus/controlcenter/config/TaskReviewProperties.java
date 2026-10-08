package com.argus.controlcenter.config;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 人工核对独立授权；关闭动作投递不妨碍核对，但全局只读仍优先。 */
@ConfigurationProperties(prefix="argus.task-review")
public class TaskReviewProperties {
    private boolean enabled;
    private Set<String> allowedInstanceIds=new LinkedHashSet<>();
    private Duration lease=Duration.ofSeconds(60);
    private Duration retryDelay=Duration.ofSeconds(3);
    private int maxAttempts=5;
    public boolean isEnabled(){return enabled;}
    public void setEnabled(boolean v){enabled=v;}
    public Set<String> getAllowedInstanceIds(){return allowedInstanceIds;}
    public void setAllowedInstanceIds(Set<String> v){allowedInstanceIds=v==null?new LinkedHashSet<>():new LinkedHashSet<>(v);}
    public Duration getLease(){return lease;}
    public void setLease(Duration v){lease=v;}
    public Duration getRetryDelay(){return retryDelay;}
    public void setRetryDelay(Duration v){retryDelay=v;}
    public int getMaxAttempts(){return maxAttempts;}
    public void setMaxAttempts(int v){maxAttempts=v;}
}
