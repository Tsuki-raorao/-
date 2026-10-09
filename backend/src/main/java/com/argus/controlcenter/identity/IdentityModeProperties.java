package com.argus.controlcenter.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 身份模式的 fail-closed 配置；OIDC 启用后不会自动回退旧共享令牌。 */
@ConfigurationProperties(prefix = "argus.identity")
public class IdentityModeProperties {
    public enum Mode { LEGACY_TOKEN, OIDC_IDENTITY }
    private Mode mode = Mode.LEGACY_TOKEN;
    private String issuer = "";
    private String audience = "";
    private String clientRegistration = "argus";
    private String bootstrapIssuer = "";
    private String bootstrapSubject = "";

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.LEGACY_TOKEN : mode; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer == null ? "" : issuer.trim(); }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience == null ? "" : audience.trim(); }
    public String getClientRegistration() { return clientRegistration; }
    public void setClientRegistration(String clientRegistration) { this.clientRegistration = clientRegistration == null ? "" : clientRegistration.trim(); }
    public String getBootstrapIssuer() { return bootstrapIssuer; }
    public void setBootstrapIssuer(String value) { bootstrapIssuer = value == null ? "" : value.trim(); }
    public String getBootstrapSubject() { return bootstrapSubject; }
    public void setBootstrapSubject(String value) { bootstrapSubject = value == null ? "" : value.trim(); }
}
