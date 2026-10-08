package com.argus.agent.service;

import com.argus.agent.AgentConfig;
import com.argus.agent.model.TaskCommand;
import com.argus.agent.model.TaskView;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** 持久接收、执行前落盘和保守恢复；未知结果保留实例互斥，不做自动重放。 */
public final class TaskService implements AutoCloseable {
    public interface ControlExecutor {
        String resolveTarget(String instanceId);
        /** 可能阻塞的准备步骤结束后，紧贴副作用入口调用校验，不能提前消费校验。 */
        void execute(String instanceId, String action, Runnable beforeSideEffect);
        String observe(String instanceId);
    }
    public record Submission(TaskView task, boolean created) { }
    private final AgentConfig config;
    private final ControlExecutor actions;
    private final Clock clock;
    private final TaskInbox inbox;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService dispatcher;
    private final Map<String, String> unresolved = new HashMap<>();
    private final Set<String> scheduled = new HashSet<>();
    private final int queueCapacity;
    private volatile boolean closing;
    private boolean started;

    public TaskService(InstanceService instances, AgentConfig config) {
        this(instances, config, Clock.systemUTC());
    }

    public TaskService(InstanceService instances, AgentConfig config, Clock clock) {
        this(config, new ControlExecutor() {
            public String resolveTarget(String id) { return instances.resolveControlTarget(id); }
            public void execute(String id, String action, Runnable beforeSideEffect) { instances.execute(id, action, beforeSideEffect); }
            public String observe(String id) { return instances.status(id).status(); }
        }, clock);
    }

    public TaskService(AgentConfig config, ControlExecutor actions) {
        this(config, actions, Clock.systemUTC());
    }

    public TaskService(AgentConfig config, ControlExecutor actions, Clock clock) {
        this.config = config; this.actions = actions; this.clock = Objects.requireNonNull(clock);
        if (config.controlEnabled() && (config.authToken().isBlank() || config.controlToken().isBlank()
                || config.controlToken().equals(config.authToken()) || config.controlInstances().isEmpty()))
            throw new IllegalArgumentException("control requires distinct tokens and instance allowlist");
        boolean open = config.controlEnabled() || config.taskDirectoryConfigured()
                || Files.exists(config.taskDirectory().resolve("store.id"));
        inbox = open ? new TaskInbox(config.taskDirectory(), config.taskMaxRecords()) : null;
        queueCapacity = Math.max(4, config.maxConcurrentTasks() * 4);
        workers = new ThreadPoolExecutor(config.maxConcurrentTasks(), config.maxConcurrentTasks(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
        dispatcher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "argus-task-dispatch"); thread.setDaemon(true); return thread;
        });
        try {
            if (inbox != null) for (TaskInbox.Entry entry : inbox.all()) {
                if (entry.task().status().equals("RUNNING")) {
                    entry = entry.transition("UNKNOWN", "AGENT_RESTARTED", null);
                    inbox.put(entry);
                }
                if (unresolved(entry.task().status())
                        && unresolved.putIfAbsent(entry.command().instanceId(), entry.command().commandId()) != null)
                    throw new TaskRejected(503, "inbox_inconsistent");
            }
        } catch (RuntimeException failure) {
            workers.shutdownNow(); dispatcher.shutdownNow(); if (inbox != null) inbox.close(); throw failure;
        }
    }

    public synchronized void start() {
        if (started || closing) return;
        started = true;
        dispatcher.scheduleWithFixedDelay(this::dispatch, 0, 100, TimeUnit.MILLISECONDS);
    }

    public synchronized String storeId() { return inbox == null ? null : inbox.storeId(); }
    public String executionMode() { return config.mock() ? "MOCK" : "DOCKER"; }
    public synchronized boolean controlEnabled() { return config.controlEnabled() && !closing && inbox != null && inbox.healthy(); }

    public synchronized Submission submit(TaskCommand command) {
        if (!config.controlEnabled() || closing) throw new TaskRejected(403, "control_disabled");
        if (inbox == null) throw new TaskRejected(503, "inbox_unavailable");
        TaskInbox.Entry previous = inbox.get(command.commandId());
        if (previous != null) {
            if (!previous.command().equals(command)) throw new TaskRejected(409, "command_conflict");
            return new Submission(previous.task(), false);
        }
        if (!inbox.healthy()) throw new TaskRejected(503, "inbox_unavailable");
        if (!config.controlInstances().contains(command.instanceId())) throw new TaskRejected(403, "instance_not_allowed");
        if (contextError(command) != null) throw new TaskRejected(409, "target_mismatch");
        if (expired(command)) throw new TaskRejected(409, "command_expired");
        if (unresolved.containsKey(command.instanceId())) throw new TaskRejected(409, "instance_busy");
        if (inbox.full()) throw new TaskRejected(503, "inbox_capacity");
        long pending = inbox.all().stream().filter(entry -> Set.of("PENDING", "RUNNING").contains(entry.task().status())).count();
        if (pending >= queueCapacity + config.maxConcurrentTasks()) throw new TaskRejected(429, "agent_busy");
        String target = actions.resolveTarget(command.instanceId());
        String now = Instant.now().toString();
        TaskView task = new TaskView(command.commandId(), command.instanceId(), command.action(), "PENDING", "ACCEPTED",
                now, null, null, executionMode(), config.nodeId(), inbox.storeId(), "ACCEPTED", null);
        inbox.put(new TaskInbox.Entry(command, target, task));
        unresolved.put(command.instanceId(), command.commandId());
        // 持久接受后，即使进程在入队前退出，PENDING 仍由恢复扫描接续。
        return new Submission(task, true);
    }

    public synchronized TaskView get(String id) {
        if (!TaskCommand.uuid(id)) throw new IllegalArgumentException("invalid command id");
        TaskInbox.Entry entry = inbox == null ? null : inbox.get(id);
        return entry == null ? null : entry.task();
    }

    private synchronized void dispatch() {
        if (!started || !controlEnabled()) return;
        for (TaskInbox.Entry entry : inbox.all()) {
            String id = entry.command().commandId();
            if (!entry.task().status().equals("PENDING") || scheduled.contains(id)) continue;
            if (workers.getQueue().remainingCapacity() == 0) break;
            scheduled.add(id);
            try { workers.execute(() -> run(id)); }
            catch (RejectedExecutionException stopped) { scheduled.remove(id); return; }
        }
    }

    private void run(String id) {
        try {
            TaskInbox.Entry entry;
            synchronized (this) {
                if (!controlEnabled()) return;
                entry = inbox.get(id);
                if (entry == null || !entry.task().status().equals("PENDING")) return;
            }
            String rejection = contextError(entry.command());
            if (rejection == null && !config.controlInstances().contains(entry.command().instanceId())) rejection = "INSTANCE_NOT_ALLOWED";
            if (rejection == null && expired(entry.command())) rejection = "EXPIRED";
            if (rejection == null) {
                try {
                    if (!entry.targetContainer().equals(actions.resolveTarget(entry.command().instanceId()))) rejection = "TARGET_CHANGED";
                } catch (RuntimeException invalid) { rejection = "TARGET_UNAVAILABLE"; }
            }
            if (rejection != null) { finish(entry.transition("FAILED", rejection, null)); return; }
            synchronized (this) {
                if (!controlEnabled()) return;
                // 预执行检查期间也可能经过截止时间，落盘前再次检查。
                if (expired(entry.command())) { finish(entry.transition("FAILED", "EXPIRED", null)); return; }
                entry = entry.transition("RUNNING", "EXECUTING", null);
                inbox.put(entry);
            }
            String observed = null;
            String result = "UNKNOWN";
            String code = "EXECUTION_UNCERTAIN";
            try {
                if (Thread.currentThread().isInterrupted() || closing) throw new IllegalStateException("stopping");
                TaskCommand command = entry.command();
                // fsync/原子替换也可能耗时：RUNNING 落盘后不能沿用落盘前的期限判断。
                checkExecutionDeadline(command);
                actions.execute(command.instanceId(), command.action(), () -> {
                    if (Thread.currentThread().isInterrupted() || closing) throw new IllegalStateException("stopping");
                    checkExecutionDeadline(command);
                });
                observed = actions.observe(entry.command().instanceId());
                String expected = entry.command().action().equals("stop") ? "STOPPED" : "RUNNING";
                if (expected.equals(observed)) { result = "SUCCEEDED"; code = "STATE_CONFIRMED"; }
                else code = "STATE_NOT_CONFIRMED";
            } catch (ExecutionExpired beforeEffect) {
                // 专用校验发生在副作用入口之前，确认没有执行才可以释放互斥。
                result = "FAILED"; code = "EXPIRED";
            } catch (RuntimeException uncertain) {
                // 调用后超时/中断/错误可能已有副作用；禁止变成可自动重试的失败。
            }
            finish(entry.transition(result, code, observed));
        } catch (TaskRejected storageFailure) {
            // Inbox 自行进入不健康状态，停止所有新执行；磁盘 RUNNING 在重启后变 UNKNOWN。
        } finally {
            synchronized (this) { scheduled.remove(id); }
        }
    }

    private synchronized void finish(TaskInbox.Entry entry) {
        inbox.put(entry);
        if (!unresolved(entry.task().status())) unresolved.remove(entry.command().instanceId(), entry.command().commandId());
    }
    private String contextError(TaskCommand command) {
        return !command.expectedNodeId().equals(config.nodeId()) || inbox == null
                || !command.expectedStoreId().equals(inbox.storeId()) || !command.expectedExecutionMode().equals(executionMode())
                ? "EXECUTION_CONTEXT_CHANGED" : null;
    }
    private boolean expired(TaskCommand command) { return !Instant.parse(command.expiresAt()).isAfter(clock.instant()); }
    private void checkExecutionDeadline(TaskCommand command) { if (expired(command)) throw new ExecutionExpired(); }
    private static final class ExecutionExpired extends RuntimeException { }
    private boolean unresolved(String status) { return Set.of("PENDING", "RUNNING", "UNKNOWN").contains(status); }

    @Override public void close() {
        synchronized (this) { if (closing) return; closing = true; }
        dispatcher.shutdownNow();
        workers.shutdownNow();
        boolean terminated;
        try { terminated = workers.awaitTermination(config.commandTimeoutSeconds() + 3L, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); terminated = workers.isTerminated(); }
        // worker 未结束时绝不释放目录锁给另一 Agent；JVM 退出后由操作系统释放。
        if (!terminated) throw new IllegalStateException("task workers are still stopping");
        if (inbox != null) inbox.close();
    }
}
