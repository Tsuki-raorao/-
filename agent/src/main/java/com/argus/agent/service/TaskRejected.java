package com.argus.agent.service;

/** 对外只返回固定错误码，不透出磁盘路径、命令输出或令牌。 */
public final class TaskRejected extends RuntimeException {
    private final int status;
    public TaskRejected(int status, String code) { super(code); this.status = status; }
    public int status() { return status; }
}
