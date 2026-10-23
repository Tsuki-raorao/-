package com.argus.controlcenter.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "argus.ai")
public class AiProperties {
    private boolean enabled;
    private String baseUrl;
    private String model;
    private String apiKey;
    private Duration timeout = Duration.ofSeconds(30);
    private int maxTokens = 800;
    private int maxConcurrentRuns = 2;
    private int maxRunSeconds = 60;
    private int maxContextChars = 64000;
    private Duration recoveryStaleAfter = Duration.ofMinutes(2);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getModel() { return model; }
    public void setModel(String value) { model = value; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String value) { apiKey = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int value) { maxTokens = value; }
    public int getMaxConcurrentRuns() { return maxConcurrentRuns; }
    public void setMaxConcurrentRuns(int value) { maxConcurrentRuns = Math.max(1, Math.min(value, 16)); }
    public int getMaxRunSeconds() { return maxRunSeconds; }
    public void setMaxRunSeconds(int value) { maxRunSeconds = Math.max(5, Math.min(value, 300)); }
    public int getMaxContextChars() { return maxContextChars; }
    public void setMaxContextChars(int value) { maxContextChars = Math.max(4000, Math.min(value, 200000)); }
    public Duration getRecoveryStaleAfter() { return recoveryStaleAfter; }
    public void setRecoveryStaleAfter(Duration value) { recoveryStaleAfter = value == null ? Duration.ofMinutes(2) : value; }
}
