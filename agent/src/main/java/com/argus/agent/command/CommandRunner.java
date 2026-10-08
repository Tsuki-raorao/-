package com.argus.agent.command;

import java.util.List;

/** 可替换的命令边界；测试注入模拟结果，不接触真实 Docker。 */
@FunctionalInterface
public interface CommandRunner {
    String run(List<String> command, int timeoutSeconds, int maxOutputBytes) throws CommandFailure;
    /** 注入的纯模拟执行器可没有外部进程；真实执行器返回它的命令作用域登记表。 */
    default ExecutionRegistry executionRegistry() { return null; }
}
