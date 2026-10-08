package com.argus.agent;

import com.argus.agent.command.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** 已知执行资源测试：只创建当前 JDK 的测试子进程和本次线程。 */
public final class ExecutionRegistryTests {
    private static Path root;
    private static int passed, failed;
    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toAbsolutePath().resolve("execution-tests-" + System.nanoTime());
        Files.createDirectories(root);
        test("unknown previous JVM is not a cleanup proof", () -> {
            ExecutionRegistry registry = new ExecutionRegistry();
            require(!registry.evidence("old").active(), "unexpected current process");
            equal("PRIOR_PROCESS_UNVERIFIED", registry.evidence("old").provenance());
        });
        test("scope remains active before finally and forbids nested ownership", () -> {
            ExecutionRegistry registry = new ExecutionRegistry();
            try (var scope = registry.open("task")) {
                require(registry.evidence("task").scopeActive(), "scope invisible");
                try { registry.open("other"); throw new AssertionError("nested scope accepted"); }
                catch (IllegalStateException expected) { }
            }
            require(!registry.evidence("task").active(), "closed scope stayed active");
            equal("LOCAL_KNOWN_PROCESSES_EXITED", registry.evidence("task").provenance());
        });
        test("live Java helper remains visible after worker scope exits", () -> {
            ExecutionRegistry registry = new ExecutionRegistry();
            Process child = child("sleep", root.resolve("live.pid").toString());
            try {
                try (var scope = registry.open("task")) { registry.register(child, new Thread()); }
                require(!registry.evidence("task").scopeActive(), "scope did not exit");
                require(registry.evidence("task").knownProcessActive(), "live helper forgotten");
                require(registry.hasLiveExecutions(), "live registry hidden");
                child.destroyForcibly(); require(child.waitFor(5, TimeUnit.SECONDS), "owned helper did not exit");
                require(!registry.evidence("task").active(), "exited helper still blocks");
            } finally { if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        });
        test("reader alive blocks even after known process exits", () -> {
            ExecutionRegistry registry = new ExecutionRegistry();
            CountDownLatch stop = new CountDownLatch(1);
            Thread reader = new Thread(() -> { try { stop.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } });
            Process child = child("fail");
            try {
                reader.start();
                try (var scope = registry.open("task")) { registry.register(child, reader); }
                require(child.waitFor(5, TimeUnit.SECONDS), "helper did not exit");
                var evidence = registry.evidence("task");
                require(!evidence.knownProcessActive() && evidence.readerActive(), "reader not independently tracked");
                stop.countDown(); reader.join(5000);
                require(!registry.evidence("task").active(), "reader cleanup not observed");
            } finally { stop.countDown(); reader.interrupt(); reader.join(5000); if (child.isAlive()) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); } }
        });
        test("real runner output failure leaves no owned process or reader", () -> {
            ProcessCommandRunner runner = new ProcessCommandRunner();
            try (var scope = runner.executionRegistry().open("task")) {
                try { runner.run(command("large"), 10, 1024); throw new AssertionError("output limit missed"); }
                catch (CommandFailure expected) { equal(CommandFailure.Reason.OUTPUT_LIMIT, expected.reason()); }
                var evidence = runner.executionRegistry().evidence("task");
                require(evidence.scopeActive() && !evidence.knownProcessActive() && !evidence.readerActive(), "owned resources survived");
            }
            require(!runner.executionRegistry().evidence("task").active(), "runner scope stayed active");
        });
        System.out.println("Execution registry tests: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
    private static List<String> command(String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + root, "-cp", System.getProperty("java.class.path"), AgentTestChild.class.getName()));
        command.addAll(List.of(args)); return command;
    }
    private static Process child(String... args) throws Exception { return new ProcessBuilder(command(args)).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
    private interface Checked { void run() throws Exception; }
    private static void test(String name, Checked action) { try { action.run(); passed++; System.out.println("[PASS] " + name); } catch (Throwable error) { failed++; System.err.println("[FAIL] " + name + " - " + error); } }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + " but was " + actual); }
}
