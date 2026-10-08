package com.argus.controlcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 控制中心访问节点 Agent 的安全配置。
 *
 * <p>默认关闭远程访问，并且只允许只读接口。生产环境必须显式开启，
 * 同时配置节点地址白名单；这样即使数据库中的节点地址被误写，也不会
 * 让控制中心成为任意内网地址的 HTTP 代理。</p>
 */
@ConfigurationProperties(prefix = "argus.agent")
public class AgentGatewayProperties {
    /** 是否启用控制中心到 Agent 的远程读取。默认关闭，避免误连真实服务器。 */
    private boolean enabled = false;
    /** 是否保持只读模式。当前版本只实现 true 的读取路径。 */
    private boolean readOnly = true;
    /** 可靠任务的额外控制 gate；默认关闭，原只读 GET 网关仍保持 readOnly=true。 */
    private boolean allowControl = false;
    /** 是否强制校验目标主机白名单。默认强制。 */
    private boolean requireAllowlist = true;
    /** 允许访问的 Agent 主机名或 IP；不含协议和端口。 */
    private Set<String> allowedHosts = new LinkedHashSet<>();
    /** 可选的共享 Bearer Token，通过环境变量注入，不能写入仓库。 */
    private String authToken = "";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }
    public boolean isAllowControl() { return allowControl; }
    public void setAllowControl(boolean allowControl) { this.allowControl = allowControl; }
    public boolean isRequireAllowlist() { return requireAllowlist; }
    public void setRequireAllowlist(boolean requireAllowlist) { this.requireAllowlist = requireAllowlist; }
    public Set<String> getAllowedHosts() { return allowedHosts; }
    public void setAllowedHosts(Set<String> allowedHosts) {
        this.allowedHosts = allowedHosts == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedHosts);
    }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken == null ? "" : authToken; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
}
