package com.argus.agent.command;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.argus.agent.command.CommandFailure.Reason;

/** 参数数组启动子进程，边读输出边等待退出；统一限制总时间与输出字节数。 */
public final class ProcessCommandRunner implements CommandRunner {
    private final ExecutionRegistry executions = new ExecutionRegistry();
    @Override public ExecutionRegistry executionRegistry() { return executions; }
    @Override
    public String run(List<String> command, int timeoutSeconds, int maxOutputBytes) {
        if (timeoutSeconds <= 0 || maxOutputBytes <= 0) throw new IllegalArgumentException("invalid command limits");
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException | RuntimeException failure) {
            throw new CommandFailure(Reason.START_FAILED);
        }
        // reader 创建后才可把两类资源一起登记；读取函数通过固定 holder 引用本次进程树。
        ExecutionRegistry.OwnedProcess[] holder = new ExecutionRegistry.OwnedProcess[1];
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        FutureTask<byte[]> readOutput = new FutureTask<>(() -> {
            try (var stream = process.getInputStream(); var output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > maxOutputBytes - output.size()) {
                        // 截断的容器清单不能当作完整清单返回；先停止进程，再明确报告超限。
                        holder[0].terminate();
                        throw new CommandFailure(Reason.OUTPUT_LIMIT);
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        });
        Thread reader = new Thread(readOutput, "argus-command-output");
        reader.setDaemon(true);
        ExecutionRegistry.OwnedProcess processTree = executions.register(process, reader);
        holder[0] = processTree;
        try {
            reader.start();
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
            // kill 请求与限时等待并非退出证明；仍活资源保留在登记表，阻止人工核对放行。
            if (processTree.processesAlive() || processTree.readerAlive()) throw new CommandFailure(Reason.CLEANUP_UNCONFIRMED);
        }
    }

    private static long remaining(long deadline) { return Math.max(0, deadline - System.nanoTime()); }

}
