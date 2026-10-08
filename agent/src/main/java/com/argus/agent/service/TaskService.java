package com.argus.agent.service;

import com.argus.agent.AgentConfig;
import com.argus.agent.model.TaskCommand;
import com.argus.agent.model.TaskView;
import com.argus.agent.model.ResolutionRequest;
import com.argus.agent.model.ResolutionReceipt;
import com.argus.agent.model.ReviewEvidence;
import com.argus.agent.command.ExecutionRegistry;
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
        default ExecutionRegistry executionRegistry() { return null; }
    }
    public record Submission(TaskView task, boolean created) { }
    public record ResolutionSubmission(ResolutionReceipt receipt, boolean created) { }
    private final AgentConfig config;
    private final ControlExecutor actions;
    private final Clock clock;
    private final TaskInbox inbox;
    private final ExecutionRegistry executions;
    private final ThreadPoolExecutor workers;
    private final ScheduledExecutorService dispatcher;
    private final Map<String, String> unresolved = new HashMap<>();
    private final Set<String> scheduled = new HashSet<>();
    private final Map<String, Object> reviewMonitors = new ConcurrentHashMap<>();
    private final int queueCapacity;
    private volatile boolean closing;
    private boolean closed;
    private boolean started;

    public TaskService(InstanceService instances, AgentConfig config) {
        this(instances, config, Clock.systemUTC());
    }

    public TaskService(InstanceService instances, AgentConfig config, Clock clock) {
        this(config, new ControlExecutor() {
            public String resolveTarget(String id) { return instances.resolveControlTarget(id); }
            public void execute(String id, String action, Runnable beforeSideEffect) { instances.execute(id, action, beforeSideEffect); }
            public String observe(String id) { return instances.status(id).status(); }
            public ExecutionRegistry executionRegistry() { return instances.executionRegistry(); }
        }, clock);
    }

    public TaskService(AgentConfig config, ControlExecutor actions) {
        this(config, actions, Clock.systemUTC());
    }

    public TaskService(AgentConfig config, ControlExecutor actions, Clock clock) {
        this.config = config; this.actions = actions; this.clock = Objects.requireNonNull(clock);
        ExecutionRegistry provided = actions.executionRegistry();
        this.executions = provided == null ? new ExecutionRegistry() : provided;
        if (config.controlEnabled() && (config.authToken().isBlank() || config.controlToken().isBlank()
                || config.controlToken().equals(config.authToken()) || config.controlInstances().isEmpty()))
            throw new IllegalArgumentException("control requires distinct tokens and instance allowlist");
        boolean open = config.controlEnabled() || config.reviewEnabled() || config.taskDirectoryConfigured()
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
                if (unresolved(entry.task().status()) && inbox.resolution(entry.command().commandId()) == null
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
    public synchronized boolean reviewEnabled() { return config.reviewEnabled() && !closing && inbox != null && inbox.healthy(); }

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

    public synchronized ResolutionReceipt resolution(String commandId, String resolutionId) {
        if (!TaskCommand.uuid(commandId) || !TaskCommand.uuid(resolutionId)) throw new IllegalArgumentException("invalid resolution id");
        ResolutionReceipt value = inbox == null ? null : inbox.resolution(commandId);
        return value != null && value.request().resolutionId().equals(resolutionId) ? value : null;
    }

    public synchronized ReviewEvidence reviewEvidence(String commandId) {
        if (!TaskCommand.uuid(commandId)) throw new IllegalArgumentException("invalid command id");
        TaskInbox.Entry entry = inbox == null ? null : inbox.get(commandId);
        if (entry == null) return null;
        ExecutionRegistry.Evidence original = executions.evidence(commandId);
        ExecutionRegistry.Evidence observation = executions.evidence(reviewScope(commandId));
        String lockOwner = unresolved.get(entry.command().instanceId());
        return new ReviewEvidence(entry.task(), Instant.now().toString(), reviewEnabled(), config.reviewInstances().contains(entry.command().instanceId()),
                scheduled.contains(commandId) || original.scopeActive() || observation.scopeActive(),
                original.knownProcessActive() || original.readerActive() || observation.knownProcessActive() || observation.readerActive(),
                lockOwner == null ? "NONE" : lockOwner.equals(commandId) ? "OWNED_BY_COMMAND" : "OWNED_BY_OTHER",
                original.provenance().equals("PRIOR_PROCESS_UNVERIFIED") ? "PRIOR_PROCESS_UNVERIFIED" : "CURRENT_PROCESS_CLEARED");
    }

    /** 相同命令串行核对，其余任务的 worker 不被慢 Docker 观察占住 TaskService 监视器。 */
    public ResolutionSubmission resolve(ResolutionRequest request) {
        synchronized (this) {
            reviewPermission(request);
            ResolutionReceipt previous = repeatedResolution(request);
            if (previous != null) return new ResolutionSubmission(previous, false);
            // 不为任意不存在的 UUID 分配长期监视器，数量受持久 Inbox 任务容量限制。
            if (inbox == null || inbox.get(request.commandId()) == null) throw new TaskRejected(404, "task_not_found");
        }
        Object monitor = reviewMonitors.computeIfAbsent(request.commandId(), ignored -> new Object());
        synchronized (monitor) {
            TaskInbox.Entry entry;
            String assessment;
            synchronized (this) {
                reviewPermission(request);
                ResolutionReceipt previous = repeatedResolution(request);
                if (previous != null) return new ResolutionSubmission(previous, false);
                entry = reviewGate(request);
                assessment = reviewEvidence(request.commandId()).processAssessment();
            }
            String observed = null, observationStatus = "UNAVAILABLE";
            try (ExecutionRegistry.Scope scope = executions.open(reviewScope(request.commandId()))) {
                try {
                    // 别名指向变化时不能把另一个容器的现状当作原任务核对证据。
                    if (!entry.targetContainer().equals(actions.resolveTarget(request.instanceId()))) throw new TaskRejected(409, "review_target_mismatch");
                    String state = actions.observe(request.instanceId());
                    observed = Set.of("RUNNING", "STOPPED").contains(state == null ? "" : state) ? state : "UNKNOWN";
                    observationStatus = "AVAILABLE";
                } catch (TaskRejected rejected) { throw rejected; }
                catch (RuntimeException unavailable) { /* 观察失败只记录不可用，绝不填造容器状态。 */ }
            }
            String observedAt = Instant.now().toString();
            synchronized (this) {
                // 观察也可能产生未结束的已知进程；检查必须在观察结束后再次执行。
                TaskInbox.Entry current = reviewGate(request);
                if (!current.equals(entry)) throw new TaskRejected(409, "review_task_changed");
                ResolutionReceipt receipt = new ResolutionReceipt(request, entry.task(), Instant.now().toString(),
                        entry.task().status().equals("UNKNOWN") ? "ACKNOWLEDGED_UNKNOWN" : "CONFIRMED_TERMINAL",
                        assessment, observationStatus, observed, observedAt);
                inbox.putResolution(receipt);
                // 落盘后的崩溃也由回执重建；不得按实例名无条件删锁，更不能在重复回执时再删。
                unresolved.remove(request.instanceId(), request.commandId());
                return new ResolutionSubmission(receipt, true);
            }
        }
    }
    private ResolutionReceipt repeatedResolution(ResolutionRequest request) {
        if (inbox == null) return null;
        String owner = inbox.resolutionOwner(request.resolutionId());
        if (owner != null && !owner.equals(request.commandId())) throw new TaskRejected(409, "resolution_conflict");
        ResolutionReceipt previous = inbox.resolution(request.commandId());
        if (previous != null && !previous.request().equals(request)) throw new TaskRejected(409, "resolution_conflict");
        return previous;
    }
    /** 重复 POST 也受当前写授权约束；但不再检查旧锁、重新观察或释放锁。 */
    private void reviewPermission(ResolutionRequest request) {
        if (!config.reviewEnabled() || closing) throw new TaskRejected(403, "review_disabled");
        if (inbox == null || !inbox.healthy()) throw new TaskRejected(503, "inbox_unavailable");
        if (!config.reviewInstances().contains(request.instanceId())) throw new TaskRejected(403, "review_instance_not_allowed");
    }
    private TaskInbox.Entry reviewGate(ResolutionRequest request) {
        reviewPermission(request);
        TaskInbox.Entry entry = inbox.get(request.commandId());
        if (entry == null) throw new TaskRejected(404, "task_not_found");
        if (!request.matches(entry.command()) || contextError(entry.command()) != null) throw new TaskRejected(409, "review_target_mismatch");
        ReviewEvidence evidence = reviewEvidence(request.commandId());
        if (evidence.workerActive() || evidence.knownProcessesActive()) throw new TaskRejected(409, "review_executor_active");
        if (!Set.of("UNKNOWN", "SUCCEEDED", "FAILED").contains(entry.task().status())) throw new TaskRejected(409, "review_task_not_closed");
        if (evidence.lockDisposition().equals("OWNED_BY_OTHER")
                || entry.task().status().equals("UNKNOWN") && !evidence.lockDisposition().equals("OWNED_BY_COMMAND"))
            throw new TaskRejected(409, "review_lock_mismatch");
        return entry;
    }
    private String reviewScope(String commandId) { return "review:" + commandId; }

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
        try (ExecutionRegistry.Scope scope = executions.open(id)) {
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
        synchronized (this) { if (closed) return; closing = true; }
        dispatcher.shutdownNow();
        workers.shutdownNow();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.commandTimeoutSeconds() + 3L);
        try {
            boolean terminated;
            try { terminated = workers.awaitTermination(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException stopped) { interrupted = true; terminated = workers.isTerminated(); }
            // worker 及 HTTP 核对的观察作用域都必须退出；不能先把目录锁交给另一 Agent。
            if (!terminated) throw new IllegalStateException("task workers are still stopping");
            while (executions.hasLiveExecutions() && System.nanoTime() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException stopped) { interrupted = true; }
            }
            if (executions.hasLiveExecutions()) throw new IllegalStateException("known execution resources are still stopping");
            if (inbox != null) inbox.close();
            synchronized (this) { closed = true; }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
