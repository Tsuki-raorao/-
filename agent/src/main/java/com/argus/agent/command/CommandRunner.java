package com.argus.agent.command;

import java.util.List;

/** 可替换的命令边界；测试注入模拟结果，不接触真实 Docker。 */
@FunctionalInterface
public interface CommandRunner {
    String run(List<String> command, int timeoutSeconds, int maxOutputBytes) throws CommandFailure;
}
