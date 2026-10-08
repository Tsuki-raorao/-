package com.argus.controlcenter.exception;

/** 固定、脱敏的控制边界错误，不携带远端正文或凭据。 */
public class TaskControlException extends RuntimeException {
    private final int status;
    public TaskControlException(int status, String code) { super(code); this.status = status; }
    public int getStatus() { return status; }
}
