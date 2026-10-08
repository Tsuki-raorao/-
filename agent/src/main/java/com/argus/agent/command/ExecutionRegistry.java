package com.argus.agent.command;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** 只登记本次命令启动的进程与读取线程；不扫描或终止其它服务的进程。 */
public final class ExecutionRegistry {
    public record Evidence(boolean scopeActive, boolean knownProcessActive, boolean readerActive, String provenance) {
        public boolean active() { return scopeActive || knownProcessActive || readerActive; }
    }
    private static final class State {
        int scopes;
        final Set<OwnedProcess> processes = new LinkedHashSet<>();
    }
    private final Map<String, State> commands = new HashMap<>();
    private final ThreadLocal<State> current = new ThreadLocal<>();

    public final class Scope implements AutoCloseable {
        private final State state;
        private final Thread owner = Thread.currentThread();
        private boolean closed;
        private Scope(State state) { this.state = state; }
        @Override public void close() {
            if (Thread.currentThread() != owner) throw new IllegalStateException("execution scope belongs to another thread");
            synchronized (ExecutionRegistry.this) {
                if (closed) return;
                closed = true;
                state.scopes--;
                current.remove();
            }
        }
    }

    public synchronized Scope open(String commandId) {
        if (commandId == null || commandId.isBlank() || current.get() != null) throw new IllegalStateException("invalid execution scope");
        State state = commands.computeIfAbsent(commandId, ignored -> new State());
        if (state.scopes != 0) throw new IllegalStateException("execution scope already active");
        state.scopes++;
        current.set(state);
        return new Scope(state);
    }

    /** 注册必须在启动 reader 前完成。测试也可登记自己创建的 Java helper，验证清理失败时拒绝核对。 */
    public synchronized OwnedProcess register(Process process, Thread reader) {
        OwnedProcess owned = new OwnedProcess(process, reader);
        State state = current.get();
        if (state != null) state.processes.add(owned);
        return owned;
    }

    public synchronized Evidence evidence(String commandId) {
        State state = commands.get(commandId);
        if (state == null) return new Evidence(false, false, false, "PRIOR_PROCESS_UNVERIFIED");
        boolean process = false, reader = false;
        for (Iterator<OwnedProcess> iterator = state.processes.iterator(); iterator.hasNext();) {
            OwnedProcess owned = iterator.next();
            boolean liveProcess = owned.processesAlive();
            boolean liveReader = owned.readerAlive();
            process |= liveProcess; reader |= liveReader;
            if (state.scopes == 0 && !liveProcess && !liveReader) iterator.remove();
        }
        return new Evidence(state.scopes != 0, process, reader, "LOCAL_KNOWN_PROCESSES_EXITED");
    }

    public synchronized boolean hasLiveExecutions() {
        return commands.keySet().stream().anyMatch(id -> evidence(id).active());
    }

    /** 保存句柄后再结束父进程；即使父先退出，也继续跟踪已知后代和 reader。 */
    public static final class OwnedProcess {
        private final Process parent;
        private final Thread reader;
        private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
        private boolean inspectionFailed;
        private OwnedProcess(Process parent, Thread reader) { this.parent = Objects.requireNonNull(parent); this.reader = Objects.requireNonNull(reader); }
        public synchronized void capture() {
            try {
                parent.descendants().forEach(descendants::add);
                for (ProcessHandle child : List.copyOf(descendants)) child.descendants().forEach(descendants::add);
            } catch (RuntimeException unavailable) { inspectionFailed = true; }
        }
        public synchronized void terminate() {
            capture();
            try {
                if (parent.isAlive()) parent.destroyForcibly();
                for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
            } catch (RuntimeException unavailable) { inspectionFailed = true; }
        }
        public void awaitTermination() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            parent.waitFor(remaining(deadline), TimeUnit.NANOSECONDS);
            List<ProcessHandle> snapshot;
            synchronized (this) { snapshot = List.copyOf(descendants); }
            for (ProcessHandle child : snapshot) {
                try { child.onExit().get(remaining(deadline), TimeUnit.NANOSECONDS); }
                catch (ExecutionException | TimeoutException unavailable) { /* 有限等待后必须由 processesAlive 再确认，不能当成已清理。 */ }
            }
        }
        public synchronized boolean processesAlive() {
            capture();
            return inspectionFailed || parent.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive);
        }
        public boolean readerAlive() { return reader.isAlive(); }
        private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }
    }
}
