package com.argus.controlcenter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 控制中心的最小生产保护开关。
 *
 * <p>当前部署先以只读监控为主。只读开关打开后，所有 API 写请求都会
 * 在进入业务层前返回 405，避免公网域名解封后未完成认证的写接口被滥用。
 * 本地开发默认关闭，便于调试数据写入和任务流程。</p>
 */
@ConfigurationProperties(prefix = "argus.security")
public class SecurityProperties {
    /** 生产只读模式；默认关闭，仅本地开发使用写接口。 */
    private boolean readOnly = false;

    /** 是否要求浏览器访问 API 时提供 Bearer 令牌；默认关闭，便于本地开发。 */
    private boolean apiAuthRequired = false;

    /** API 访问令牌，只从环境变量注入，不写入配置文件或日志。 */
    private String apiAccessToken = "";

    public boolean isReadOnly() { return readOnly; }
    public void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    public boolean isApiAuthRequired() { return apiAuthRequired; }
    public void setApiAuthRequired(boolean apiAuthRequired) { this.apiAuthRequired = apiAuthRequired; }

    public String getApiAccessToken() { return apiAccessToken; }
    public void setApiAccessToken(String apiAccessToken) { this.apiAccessToken = apiAccessToken == null ? "" : apiAccessToken; }
}
