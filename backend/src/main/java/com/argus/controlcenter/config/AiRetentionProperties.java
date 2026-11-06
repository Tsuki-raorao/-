package com.argus.controlcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Retention policy for terminal Agent evidence and scoped memory. */
@ConfigurationProperties(prefix = "argus.ai.retention")
public class AiRetentionProperties {
    private boolean enabled = true;
    private int retentionDays = 30;
    private int batchSize = 200;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int value) { retentionDays = Math.max(1, Math.min(value, 3650)); }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int value) { batchSize = Math.max(10, Math.min(value, 2000)); }
}
