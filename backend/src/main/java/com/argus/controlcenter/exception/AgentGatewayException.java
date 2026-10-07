package com.argus.controlcenter.exception;

/** Agent 读取失败时使用的安全异常，不把令牌或完整远端响应返回给浏览器。 */
public class AgentGatewayException extends RuntimeException {
    private final int status;

    public AgentGatewayException(int status, String message) {
        super(message);
        this.status = status;
    }

    public AgentGatewayException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int getStatus() { return status; }
}
