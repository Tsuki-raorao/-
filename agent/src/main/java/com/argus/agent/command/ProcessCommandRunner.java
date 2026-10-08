package com.argus.agent.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.argus.agent.command.CommandFailure.Reason;

/** 参数数组启动子进程，边读输出边等待退出；统一限制总时间与输出字节数。 */
public final class ProcessCommandRunner implements CommandRunner {
    @Override
    public String run(List<String> command, int timeoutSeconds, int maxOutputBytes) {
        if (timeoutSeconds <= 0 || maxOutputBytes <= 0) throw new IllegalArgumentException("invalid command limits");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException failure) {
            throw new CommandFailure(Reason.START_FAILED);
        }
        ProcessTree processTree = new ProcessTree(process);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        FutureTask<byte[]> readOutput = new FutureTask<>(() -> {
            try (var stream = process.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > maxOutputBytes - output.size()) {
                        // 截断的容器清单不能当作完整清单返回；先停止进程，再明确报告超限。
                        processTree.terminate();
                        throw new CommandFailure(Reason.OUTPUT_LIMIT);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        });
        Thread reader = new Thread(readOutput, "argus-command-output");
        reader.setDaemon(true);
        reader.start();
        try {
            process.getOutputStream().close();
            // 等待期间记录本进程的后代，终止前再补一次快照，不按系统进程名查杀。
            while (true) {
                processTree.capture();
                if (process.waitFor(Math.min(remaining(deadline), TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS)) break;
                if (remaining(deadline) == 0) throw new CommandFailure(Reason.TIMED_OUT);
            }
            // 先取读取异常，让输出超限不会被误报成进程的非零退出码。
            byte[] output = readOutput.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if (process.exitValue() != 0) throw new CommandFailure(Reason.EXIT_FAILED);
            return new String(output, StandardCharsets.UTF_8);
        } catch (TimeoutException timeout) {
            throw new CommandFailure(Reason.TIMED_OUT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CommandFailure(Reason.INTERRUPTED);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof CommandFailure commandFailure) throw commandFailure;
            throw new CommandFailure(Reason.IO_FAILED);
        } catch (IOException failure) {
            throw new CommandFailure(Reason.IO_FAILED);
        } finally {
            // 读取线程可能已因输出超限终止父进程；无论父进程是否存活都清理已记录的后代。
            boolean interrupted = Thread.interrupted();
            processTree.terminate();
            try { processTree.awaitTermination(); } catch (InterruptedException stopped) { interrupted = true; }
            try { process.getInputStream().close(); } catch (IOException ignored) { }
            try { process.getErrorStream().close(); } catch (IOException ignored) { }
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            readOutput.cancel(true);
            try { reader.join(1000); } catch (InterruptedException stopped) { interrupted = true; }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }

    /** 只跟踪本次启动的进程树，保存句柄后再终止父进程，避免父退出后丢失后代关系。 */
    private static final class ProcessTree {
        private final Process parent;
        private final Set<ProcessHandle> descendants = new LinkedHashSet<>();
        private ProcessTree(Process parent) { this.parent = parent; }

        synchronized void capture() {
            parent.descendants().forEach(descendants::add);
            // 父进程已退出时，仍可通过此前记录的后代继续找到孙进程。
            for (ProcessHandle child : List.copyOf(descendants)) child.descendants().forEach(descendants::add);
        }

        synchronized void terminate() {
            capture();
            // 先固定后代清单，再终止父进程和清单里的进程；重复调用也是安全的。
            if (parent.isAlive()) parent.destroyForcibly();
            for (ProcessHandle child : descendants) if (child.isAlive()) child.destroyForcibly();
        }

        void awaitTermination() throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            parent.waitFor(remaining(deadline), TimeUnit.NANOSECONDS);
            List<ProcessHandle> snapshot;
            synchronized (this) { snapshot = List.copyOf(descendants); }
            for (ProcessHandle child : snapshot) {
                try { child.onExit().get(remaining(deadline), TimeUnit.NANOSECONDS); }
                catch (ExecutionException | TimeoutException ignored) { /* 清理有时间上限，不无限阻塞调用方。 */ }
            }
        }
    }
}
