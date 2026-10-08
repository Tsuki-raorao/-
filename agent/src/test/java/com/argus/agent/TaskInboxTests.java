package com.argus.agent;

import com.argus.agent.model.TaskCommand;
import com.argus.agent.model.TaskView;
import com.argus.agent.service.*;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 持久任务独立测试，所有动作均为计数器或内存 mock，不调用真实 Docker。 */
public final class TaskInboxTests {
    static final String READ = "test-only-read-token";
    static final String CONTROL = "test-only-control-token";
    private static Path root;
    private static int passed;
    private static int failed;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        root = Path.of(args[0]).toAbsolutePath().resolve("inbox-tests-" + System.nanoTime());
        Files.createDirectories(root);
        test("strict seven-string JSON rejects ambiguity", () -> {
            TaskCommand command = command(UUID.randomUUID().toString(), "node-test", UUID.randomUUID().toString(), "MOCK", "sample", "restart", future());
            equal(command, TaskCommand.parse(command.json()));
            for (String bad : List.of(command.json() + " garbage", command.json().replace("\"commandId\":", "\"commandId\":null,\"commandId\":"),
                    command.json().replace("\"restart\"", "{\"nested\":\"restart\"}"), command.json().replace("\"restart\"", "12"),
                    command.json().replace("\"action\"", "\"unknown\""), command.json().replace("\"restart\"", "\"bad\\x\""),
                    command.json().replace("\"restart\"", "\"\\u+041\""))) {
                try { TaskCommand.parse(bad); throw new AssertionError("invalid JSON accepted"); } catch (IllegalArgumentException expected) { }
            }
        });
        test("readonly default does not create Inbox", () -> {
            Path directory = folder("readonly").resolve("absent");
            AgentConfig config = config(directory, false, true, 100, false);
            try (TaskService service = new TaskService(config, new Counting())) {
                equal(null, service.storeId()); require(!Files.exists(directory), "readonly created storage");
            }
        });
        test("concurrent retries execute once and conflict remains 409", () -> {
            Counting counter = new Counting();
            try (TaskService service = new TaskService(config(folder("dedupe"), true, true, 100, true), counter)) {
                TaskCommand command = request(service, "sample", "restart");
                ExecutorService callers = Executors.newFixedThreadPool(8);
                try {
                    List<Future<TaskService.Submission>> results = new ArrayList<>();
                    for (int i = 0; i < 24; i++) results.add(callers.submit(() -> service.submit(command)));
                    int created = 0;
                    for (var result : results) if (result.get(5, TimeUnit.SECONDS).created()) created++;
                    equal(1, created);
                } finally { callers.shutdownNow(); }
                rejected(409, "command_conflict", () -> service.submit(new TaskCommand(command.commandId(), command.instanceId(), "stop",
                        command.expectedNodeId(), command.expectedStoreId(), command.expectedExecutionMode(), command.expiresAt())));
                service.start();
                TaskView done = await(service, command.commandId(), "SUCCEEDED");
                equal(command.commandId(), done.taskId()); equal(1, counter.calls.get());
                equal("MOCK", done.executionMode()); equal("RUNNING", done.observedStatus());
                require(done.startedAt() != null && done.finishedAt() != null, "missing execution timestamps");
                equal(false, service.submit(command).created()); equal(1, counter.calls.get());
            }
        });
        test("target identities deadline and allowlist are checked", () -> {
            Counting counter = new Counting();
            try (TaskService service = new TaskService(config(folder("guards"), true, true, 100, true), counter)) {
                TaskCommand normal = request(service, "sample", "restart");
                rejected(403, "instance_not_allowed", () -> service.submit(request(service, "other", "restart")));
                rejected(409, "target_mismatch", () -> service.submit(command(normal.commandId(), "another-node", service.storeId(), "MOCK", "sample", "restart", future())));
                rejected(409, "target_mismatch", () -> service.submit(command(normal.commandId(), "node-test", UUID.randomUUID().toString(), "MOCK", "sample", "restart", future())));
                rejected(409, "target_mismatch", () -> service.submit(command(normal.commandId(), "node-test", service.storeId(), "DOCKER", "sample", "restart", future())));
                rejected(409, "command_expired", () -> service.submit(command(normal.commandId(), "node-test", service.storeId(), "MOCK", "sample", "restart", Instant.now().minusSeconds(1).toString())));
                equal(0, counter.calls.get());
            }
        });
        test("pending and unknown hold per-instance exclusion", () -> {
            Counting counter = new Counting(); counter.uncertain = true;
            try (TaskService service = new TaskService(config(folder("unknown"), true, true, 100, true), counter)) {
                TaskCommand command = request(service, "sample", "restart");
                service.submit(command);
                rejected(409, "instance_busy", () -> service.submit(request(service, "sample", "stop")));
                service.start(); await(service, command.commandId(), "UNKNOWN");
                rejected(409, "instance_busy", () -> service.submit(request(service, "sample", "stop")));
                equal(1, counter.calls.get());
            }
        });
        test("completed result and store identity survive reopen", () -> {
            Path dir = folder("completed"); Counting counter = new Counting(); TaskCommand command; String store;
            try (TaskService service = new TaskService(config(dir, true, true, 100, true), counter)) {
                command = request(service, "sample", "stop"); store = service.storeId();
                service.submit(command); service.start(); equal("STOPPED", await(service, command.commandId(), "SUCCEEDED").observedStatus());
            }
            try (TaskService reopened = new TaskService(config(dir, false, true, 100, true), counter)) {
                equal(store, reopened.storeId()); equal("SUCCEEDED", reopened.get(command.commandId()).status()); equal(1, counter.calls.get());
            }
        });
        test("persisted pending resumes after reopen", () -> {
            Path dir = folder("pending"); Counting counter = new Counting(); TaskCommand command;
            try (TaskService service = new TaskService(config(dir, true, true, 100, true), counter)) {
                command = request(service, "sample", "restart"); service.submit(command);
            }
            try (TaskService reopened = new TaskService(config(dir, true, true, 100, true), counter)) {
                reopened.start(); await(reopened, command.commandId(), "SUCCEEDED"); equal(1, counter.calls.get());
            }
        });
        test("changed execution mode does not replay pending", () -> recoveryGuard("mode"));
        test("changed target does not replay pending", () -> recoveryGuard("target"));
        test("expired pending does not execute", () -> recoveryGuard("expiry"));
        test("deadline crossed by RUNNING persistence prevents side effect", () -> {
            Path dir = folder("expiry-after-persist"); Counting counter = new Counting();
            Instant before = Instant.now(); AtomicBoolean durableRunningSeen = new AtomicBoolean();
            AtomicReference<Path> record = new AtomicReference<>();
            Clock clock = testClock(() -> {
                try {
                    Path file = record.get();
                    // 只在 RUNNING 已经实际写到原子文件后推进时间，模拟慢 fsync 跨过期限。
                    if (file != null && Files.exists(file) && new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8)
                            .contains("\"status\":\"RUNNING\"")) durableRunningSeen.set(true);
                } catch (Exception error) { throw new IllegalStateException(error); }
                return durableRunningSeen.get() ? before.plusSeconds(120) : before;
            });
            try (TaskService service = new TaskService(config(dir, true, true, 100, true), counter, clock)) {
                TaskCommand command = command(UUID.randomUUID().toString(), "node-test", service.storeId(), "MOCK", "sample", "restart", before.plusSeconds(60).toString());
                record.set(dir.resolve(command.commandId() + ".task"));
                service.submit(command); service.start();
                TaskView result = await(service, command.commandId(), "FAILED");
                equal("EXPIRED", result.resultCode()); equal(0, counter.calls.get());
                require(durableRunningSeen.get() && result.startedAt() != null, "did not cross durable RUNNING boundary");
                equal(false, service.submit(command).created()); equal(0, counter.calls.get());
            }
        });
        test("deadline crossed by final Docker discovery prevents command", () -> {
            Path dir = folder("expiry-after-discovery"); Instant before = Instant.now();
            AtomicReference<Instant> current = new AtomicReference<>(before);
            AtomicInteger discoveries = new AtomicInteger(); AtomicInteger effects = new AtomicInteger();
            AgentConfig config = config(dir, true, false, 100, true, true);
            InstanceService instances = new InstanceService(config, (args0, timeout, maxBytes) -> {
                if (args0.get(1).equals("ps")) {
                    // 接收、RUNNING 前各发现一次；执行入口内的第三次发现才跨过截止时间。
                    if (discoveries.incrementAndGet() == 3) current.set(before.plusSeconds(120));
                    return "sample\trunning\n";
                }
                if (Set.of("start", "stop", "restart").contains(args0.get(1))) effects.incrementAndGet();
                return "";
            });
            try (TaskService service = new TaskService(instances, config, testClock(current::get))) {
                TaskCommand command = command(UUID.randomUUID().toString(), "node-test", service.storeId(), "DOCKER", "sample", "restart", before.plusSeconds(60).toString());
                service.submit(command); service.start();
                equal("EXPIRED", await(service, command.commandId(), "FAILED").resultCode());
                equal(3, discoveries.get()); equal(0, effects.get());
            }
        });
        test("exclusive directory lock rejects second process owner", () -> {
            Path dir = folder("lock");
            try (TaskService first = new TaskService(config(dir, true, true, 100, true), new Counting())) {
                rejected(503, "inbox_unavailable", () -> new TaskService(config(dir, true, true, 100, true), new Counting()));
                Process other = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Djava.io.tmpdir=" + root, "-cp", System.getProperty("java.class.path"), TaskCrashChild.class.getName(),
                        "lock-probe", dir.toString(), dir.resolve("unused-request").toString(), dir.resolve("unused-effects").toString())
                        .redirectErrorStream(true).start();
                try { require(other.waitFor(5, TimeUnit.SECONDS), "second owner hung"); equal(48, other.exitValue()); }
                finally { if (other.isAlive()) other.destroyForcibly(); }
            }
            try (TaskService reopened = new TaskService(config(dir, true, true, 100, true), new Counting())) { require(reopened.storeId() != null, "lock not released"); }
        });
        test("capacity preserves existing results", () -> {
            try (TaskService service = new TaskService(config(folder("capacity"), true, true, 1, true), new Counting())) {
                TaskCommand command = request(service, "sample", "restart");
                service.submit(command); service.start(); await(service, command.commandId(), "SUCCEEDED");
                rejected(503, "inbox_capacity", () -> service.submit(request(service, "sample", "stop")));
                equal(false, service.submit(command).created()); equal("SUCCEEDED", service.get(command.commandId()).status());
            }
        });
        test("corrupt disk record fails closed", () -> {
            Path dir = folder("corrupt"); TaskCommand command;
            try (TaskService service = new TaskService(config(dir, true, true, 100, true), new Counting())) {
                command = request(service, "sample", "restart"); service.submit(command);
            }
            Files.writeString(dir.resolve(command.commandId() + ".task"), "corrupt record");
            rejected(503, "inbox_unavailable", () -> new TaskService(config(dir, true, true, 100, true), new Counting()));
        });
        test("atomic write failure stops acceptance without execution", () -> {
            Path dir = folder("write-fail"); Counting counter = new Counting();
            try (TaskService service = new TaskService(config(dir, true, true, 100, true), counter)) {
                TaskCommand command = request(service, "sample", "restart");
                Files.createDirectory(dir.resolve(command.commandId() + ".task"));
                rejected(503, "inbox_unavailable", () -> service.submit(command));
                service.start(); require(!service.controlEnabled(), "storage failure left control enabled"); equal(0, counter.calls.get());
            }
        });
        test("HTTP independent token strict body and observable mock stop", TaskInboxTests::httpContract);
        test("real process PENDING crash resumes once", () -> crashRecovery("pending"));
        test("real process RUNNING crash becomes UNKNOWN without replay", () -> crashRecovery("running"));
        test("real process terminal crash keeps exactly one recorded effect", () -> crashRecovery("terminal"));
        test("shutdown waits for worker before releasing directory lock", () -> {
            Path dir = folder("closing"); CountDownLatch running = new CountDownLatch(1); AtomicBoolean left = new AtomicBoolean();
            Counting counter = new Counting() {
                @Override public void execute(String id, String action, Runnable beforeSideEffect) {
                    beforeSideEffect.run();
                    running.countDown();
                    try { Thread.sleep(30000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); throw new IllegalStateException("stopped"); }
                    finally { left.set(true); }
                }
            };
            TaskService service = new TaskService(config(dir, true, true, 100, true), counter);
            TaskCommand command = request(service, "sample", "restart"); service.submit(command); service.start();
            require(running.await(5, TimeUnit.SECONDS), "worker did not start"); service.close();
            require(left.get(), "lock released while worker remained");
            try (TaskService reopened = new TaskService(config(dir, true, true, 100, true), new Counting())) {
                equal("UNKNOWN", reopened.get(command.commandId()).status());
            }
        });
        Files.writeString(root.getParent().resolve("task-result.json"), Json.object("passed", passed, "failed", failed));
        System.out.println("Inbox tests: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void recoveryGuard(String scenario) throws Exception {
        Path dir = folder("recover-" + scenario); Counting first = new Counting(); TaskCommand command;
        try (TaskService service = new TaskService(config(dir, true, true, 100, true), first)) {
            command = scenario.equals("expiry") ? command(UUID.randomUUID().toString(), "node-test", service.storeId(), "MOCK", "sample", "restart", Instant.now().plusMillis(150).toString())
                    : request(service, "sample", "restart");
            service.submit(command);
        }
        if (scenario.equals("expiry")) Thread.sleep(200);
        Counting next = new Counting(); if (scenario.equals("target")) next.target = "different-container";
        try (TaskService reopened = new TaskService(config(dir, true, !scenario.equals("mode"), 100, true), next)) {
            reopened.start(); await(reopened, command.commandId(), "FAILED"); equal(0, next.calls.get());
        }
    }

    private static void httpContract() throws Exception {
        Path dir = folder("http"); AgentConfig config = config(dir, true, true, 100, true); TaskCommand command;
        try (AgentServer server = new AgentServer(config)) {
            server.start();
            String health = call(server, "/api/agent/health", READ, null).body();
            var matcher = java.util.regex.Pattern.compile("\"storeId\":\"([^\"]+)\"").matcher(health);
            require(matcher.find(), "missing store ID");
            command = command(UUID.randomUUID().toString(), "node-test", matcher.group(1), "MOCK", "sample", "stop", future());
            equal(401, call(server, "/api/agent/tasks", READ, command.json()).statusCode());
            equal(400, call(server, "/api/agent/tasks", CONTROL, command.json() + "x").statusCode());
            equal(202, call(server, "/api/agent/tasks", CONTROL, command.json()).statusCode());
            equal(200, call(server, "/api/agent/tasks", CONTROL, command.json()).statusCode());
            equal(401, call(server, "/api/agent/tasks/" + command.commandId(), READ, null).statusCode());
            String body = null;
            for (int i = 0; i < 100; i++) {
                body = call(server, "/api/agent/tasks/" + command.commandId(), CONTROL, null).body();
                if ("SUCCEEDED".equals(Json.flatObject(body).get("status"))) break;
                Thread.sleep(20);
            }
            Map<String, String> task = Json.flatObject(body);
            equal("SUCCEEDED", task.get("status")); equal("STOPPED", task.get("observedStatus"));
            equal("MOCK", task.get("executionMode")); equal(command.commandId(), task.get("taskId"));
            require(call(server, "/api/agent/instances", READ, null).body().contains("\"status\":\"STOPPED\""), "mock did not change observable state");
            Files.writeString(root.getParent().resolve("task-contract.json"), body);
        }
        try (AgentServer server = new AgentServer(config(dir, false, true, 100, true))) {
            server.start();
            equal(200, call(server, "/api/agent/tasks/" + command.commandId(), CONTROL, null).statusCode());
            equal(403, call(server, "/api/agent/tasks", CONTROL, command.json()).statusCode());
        }
    }

    private static HttpResponse<String> call(AgentServer server, String path, String token, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.listeningPort() + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer " + token);
        if (body != null) request.POST(HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void crashRecovery(String phase) throws Exception {
        Path dir = folder("crash-" + phase);
        Path commandFile = dir.resolve("command.json");
        Path countFile = dir.resolve("effects.txt");
        List<String> commandLine = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + root, "-cp", System.getProperty("java.class.path"), TaskCrashChild.class.getName(),
                phase, dir.toString(), commandFile.toString(), countFile.toString());
        Process process = new ProcessBuilder(commandLine).redirectErrorStream(true).start();
        try {
            require(process.waitFor(10, TimeUnit.SECONDS), "crash child did not exit");
            equal(47, process.exitValue());
            TaskCommand command = TaskCommand.parse(Files.readString(commandFile));
            Counting next = new Counting() {
                @Override public void execute(String id, String action, Runnable beforeSideEffect) { super.execute(id, action, beforeSideEffect); appendEffect(countFile); }
            };
            try (TaskService reopened = new TaskService(config(dir, true, true, 100, true), next)) {
                reopened.start();
                String expected = phase.equals("running") ? "UNKNOWN" : "SUCCEEDED";
                await(reopened, command.commandId(), expected);
                try (var lines = Files.lines(countFile)) { equal(1L, lines.count()); }
                equal(phase.equals("pending") ? 1 : 0, next.calls.get());
                if (phase.equals("running")) rejected(409, "instance_busy", () -> reopened.submit(request(reopened, "sample", "restart")));
            }
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    static void appendEffect(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap("effect\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))); channel.force(true);
        } catch (Exception failure) { throw new IllegalStateException("test effect failed"); }
    }
    static class Counting implements TaskService.ControlExecutor {
        final AtomicInteger calls = new AtomicInteger(); volatile String target = "sample"; volatile String state = "RUNNING"; volatile boolean uncertain;
        public String resolveTarget(String id) { return target; }
        public void execute(String id, String action, Runnable beforeSideEffect) { beforeSideEffect.run(); calls.incrementAndGet(); if (uncertain) throw new IllegalStateException("uncertain"); state = action.equals("stop") ? "STOPPED" : "RUNNING"; }
        public String observe(String id) { return state; }
    }
    static AgentConfig config(Path directory, boolean enabled, boolean mock, int capacity, boolean explicit) {
        return config(directory, enabled, mock, capacity, explicit, false);
    }
    static AgentConfig config(Path directory, boolean enabled, boolean mock, int capacity, boolean explicit, boolean discovery) {
        return new AgentConfig(0, "node-test", "node-test", READ, mock, "never-call-real-docker", "sample", "sample",
                2, 2, 8192, 4, enabled, discovery, "localhost", 1048576, 16, CONTROL, Set.of("sample"), directory, capacity, explicit);
    }
    private static Clock testClock(java.util.function.Supplier<Instant> time) {
        return new Clock() {
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return time.get(); }
        };
    }
    static TaskCommand request(TaskService service, String id, String action) {
        return command(UUID.randomUUID().toString(), "node-test", service.storeId(), service.executionMode(), id, action, future());
    }
    static TaskCommand command(String id, String node, String store, String mode, String target, String action, String expiry) {
        return new TaskCommand(id, target, action, node, store, mode, expiry);
    }
    static String future() { return Instant.now().plusSeconds(60).toString(); }
    static TaskView await(TaskService service, String id, String status) throws Exception {
        for (int i = 0; i < 250; i++) {
            TaskView task = service.get(id);
            if (task != null && task.status().equals(status)) return task;
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + status + " but got " + service.get(id).status());
    }
    private static Path folder(String name) throws Exception { Path value = root.resolve(name); Files.createDirectories(value); return value; }
    private static void rejected(int status, String code, Check check) throws Exception {
        try { check.run(); } catch (TaskRejected rejected) { equal(status, rejected.status()); equal(code, rejected.getMessage()); return; }
        throw new AssertionError("expected " + code);
    }
    private static void test(String name, Check check) {
        try { check.run(); passed++; System.out.println("[PASS] " + name); }
        catch (Throwable failure) { failed++; System.out.println("[FAIL] " + name + ": " + failure); failure.printStackTrace(System.out); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) { if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual); }
    @FunctionalInterface interface Check { void run() throws Exception; }
}
