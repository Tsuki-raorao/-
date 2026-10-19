package com.argus.controlcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix="argus.monitoring")
public class MonitoringProperties {
    private boolean enabled=false;
    private int staleSeconds=180;
    private double cpuThreshold=85;
    private double memoryThreshold=90;
    private int taskWindowSeconds=300;
    private int outboxAgeSeconds=60;
    private int queueThreshold=100;
    private int retentionDays=7;
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getStaleSeconds(){return staleSeconds;} public void setStaleSeconds(int v){if(v<1)throw new IllegalArgumentException("staleSeconds");staleSeconds=v;}
    public double getCpuThreshold(){return cpuThreshold;} public void setCpuThreshold(double v){if(!Double.isFinite(v)||v<=0)throw new IllegalArgumentException("cpuThreshold");cpuThreshold=v;}
    public double getMemoryThreshold(){return memoryThreshold;} public void setMemoryThreshold(double v){if(!Double.isFinite(v)||v<=0||v>100)throw new IllegalArgumentException("memoryThreshold");memoryThreshold=v;}
    public int getTaskWindowSeconds(){return taskWindowSeconds;} public void setTaskWindowSeconds(int v){if(v<1)throw new IllegalArgumentException("taskWindowSeconds");taskWindowSeconds=v;}
    public int getOutboxAgeSeconds(){return outboxAgeSeconds;} public void setOutboxAgeSeconds(int v){if(v<1)throw new IllegalArgumentException("outboxAgeSeconds");outboxAgeSeconds=v;}
    public int getQueueThreshold(){return queueThreshold;} public void setQueueThreshold(int v){if(v<1)throw new IllegalArgumentException("queueThreshold");queueThreshold=v;}
    public int getRetentionDays(){return retentionDays;} public void setRetentionDays(int v){if(v<1||v>90)throw new IllegalArgumentException("retentionDays");retentionDays=v;}
}
