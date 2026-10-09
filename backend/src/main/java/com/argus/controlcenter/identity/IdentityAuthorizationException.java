package com.argus.controlcenter.identity;

/** 稳定的权限错误码，不把数据库、令牌或资源存在性暴露给客户端。 */
public class IdentityAuthorizationException extends RuntimeException {
    private final String code;
    private final int status;
    public IdentityAuthorizationException(String code, int status) { super(code); this.code = code; this.status = status; }
    public String code() { return code; }
    public int status() { return status; }
}
