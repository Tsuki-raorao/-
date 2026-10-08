package com.argus.agent.command;

/** 只携带固定错误类别，绝不将命令、stderr、路径或凭据拼入 API 错误。 */
public final class CommandFailure extends RuntimeException {
    public enum Reason { START_FAILED, EXIT_FAILED, TIMED_OUT, OUTPUT_LIMIT, IO_FAILED, INTERRUPTED, INVALID_OUTPUT, CLEANUP_UNCONFIRMED }
    private final Reason reason;

    public CommandFailure(Reason reason) {
        super("Docker collection failed: " + reason.name());
        this.reason = reason;
    }

    public Reason reason() { return reason; }
}
