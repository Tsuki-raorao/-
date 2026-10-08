package com.argus.agent;

import com.argus.agent.model.TaskCommand;
import com.argus.agent.service.TaskService;
import java.nio.file.*;

/** 强制退出绕过 close/shutdown hook，用真实进程验证磁盘恢复，而不是修改内存状态。 */
public final class TaskCrashChild {
    public static void main(String[] args) throws Exception {
        String phase = args[0]; Path directory = Path.of(args[1]); Path requestFile = Path.of(args[2]); Path effects = Path.of(args[3]);
        if (phase.equals("lock-probe")) {
            try (TaskService ignored = new TaskService(TaskInboxTests.config(directory, true, true, 100, true), new TaskInboxTests.Counting())) {
                throw new AssertionError("second process acquired Inbox");
            } catch (com.argus.agent.service.TaskRejected locked) {
                if (!locked.getMessage().equals("inbox_unavailable")) throw locked;
                Runtime.getRuntime().halt(48);
            }
        }
        TaskInboxTests.Counting actions = new TaskInboxTests.Counting() {
            @Override public void execute(String id, String action, Runnable beforeSideEffect) {
                super.execute(id, action, beforeSideEffect); TaskInboxTests.appendEffect(effects);
                if (phase.equals("running")) Runtime.getRuntime().halt(47);
            }
        };
        TaskService service = new TaskService(TaskInboxTests.config(directory, true, true, 100, true), actions);
        TaskCommand command = TaskInboxTests.request(service, "sample", "restart");
        Files.writeString(requestFile, command.json());
        service.submit(command);
        if (phase.equals("pending")) Runtime.getRuntime().halt(47);
        service.start();
        TaskInboxTests.await(service, command.commandId(), "SUCCEEDED");
        Runtime.getRuntime().halt(47);
    }
}
