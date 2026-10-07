package com.argus.controlcenter.dto;

/** Agent 心跳请求；省略 status 时由服务端按在线处理。 */
public class HeartbeatRequest {
    /** 可选状态字符串，例如 ONLINE 或 OFFLINE。 */
    private String status;
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
