package com.argus.agent;

import com.argus.agent.command.CommandFailure;
import com.argus.agent.command.CommandRunner;
import com.argus.agent.command.ProcessCommandRunner;
import com.argus.agent.model.InstanceStatus;
import com.argus.agent.service.InstanceService;

import java.net.URI;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** 独立 JDK 测试入口；所有 Docker 响应均注入，子进程仅启动同一 JDK 的测试程序。 */
public final class AgentTests {
    private static final String TOKEN = "test-only-agent-token";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static final HostMetrics HOST = new HostMetrics(0d, 0L, 1024L);
    private static int passed;
    private static int failed;
    private static Path outputDirectory;

    public static void main(String[] args) throws Exception {
        outputDirectory = Path.of(args[0]).toAbsolutePath();
        Files.createDirectories(outputDirectory.resolve("tmp"));
        test("host unknown is null", () -> {
            HostMetrics value = HostMetrics.fromSystemValues(-1, -1, -1);
            equal(null, value.cpuPercent()); equal(null, value.memoryBytes());
            equal(null, value.memoryTotalBytes()); equal(null, value.memoryPercent());
            equal("UNAVAILABLE", value.metricsStatus());
        });
        test("host real zero and partial sample", () -> {
            HostMetrics zero = HostMetrics.fromSystemValues(0, 1024, 1024);
            equal(0d, zero.cpuPercent()); equal(0L, zero.memoryBytes()); equal("AVAILABLE", zero.metricsStatus());
            HostMetrics partial = HostMetrics.fromSystemValues(Double.NaN, 1024, -1);
            equal(null, partial.cpuPercent()); equal(null, partial.memoryBytes()); equal(1024L, partial.memoryTotalBytes());
            equal("PARTIAL", partial.metricsStatus());
        });
        test("JSON null nonfinite and control characters", () -> {
            String value = Json.object("missing", null, "nan", Double.NaN, "infinite", Float.POSITIVE_INFINITY,
                    "zero", 0, "text", "\b\f\u001b\u0000\n\r\t\\\"");
            require(value.contains("\"nan\":null") && value.contains("\"infinite\":null"), "invalid numeric encoding");
            require(value.contains("\\u0008") && value.contains("\\u000c") && value.contains("\\u001b")
                    && value.contains("\\u0000"), "control characters are not escaped");
            Files.writeString(outputDirectory.resolve("json-contract.json"), value);
        });
        test("mock provenance and unsupported players", () -> {
            InstanceStatus value = new InstanceService(config(true, false, "friendly", "actual"), unexpected()).list().get(0);
            equal("MOCK", value.dataSource()); equal("AVAILABLE", value.metricsStatus());
            equal(null, value.players()); equal("actual", value.container()); equal(3.2, value.cpuPercent());
            equal(value.checkedAt(), value.sampledAt()); Instant.parse(value.sampledAt());
        });
        test("successful empty discovery stays empty", () -> {
            InstanceService service = service(command -> {
                equal("ps", command.get(1)); return "";
            }, true, "default", "default");
            equal(List.of(), service.list());
        });
        test("discovery failure and malformed output never fabricate defaults", () -> {
            failure(CommandFailure.Reason.EXIT_FAILED, () -> service(command -> {
                throw new CommandFailure(CommandFailure.Reason.EXIT_FAILED);
            }, true, "default", "default").list());
            failure(CommandFailure.Reason.INVALID_OUTPUT,
                    () -> service(command -> "not a valid container row", true, "default", "default").list());
        });
        test("real zero is available", () -> {
            InstanceStatus value = sample("0%", "0B");
            equal(0d, value.cpuPercent()); equal(0L, value.memoryBytes());
            equal("AVAILABLE", value.metricsStatus()); equal("DOCKER", value.dataSource()); equal(null, value.players());
        });
        test("invalid numbers and partial metrics", () -> {
            InstanceStatus partial = sample("NaN%", "0B");
            equal(null, partial.cpuPercent()); equal(0L, partial.memoryBytes()); equal("PARTIAL", partial.metricsStatus());
            InstanceStatus missing = sample("Infinity%", "999999999999999999999TiB");
            equal(null, missing.cpuPercent()); equal(null, missing.memoryBytes()); equal("UNAVAILABLE", missing.metricsStatus());
            equal(null, sample("-1%", "no-value").cpuPercent());
            equal(250d, sample("250%", "1MiB").cpuPercent());
            equal(1048576L, sample("250%", "1MiB").memoryBytes());
        });
        test("stats command failure preserves lifecycle with null metrics", () -> {
            InstanceStatus value = service(command -> {
                if (command.get(1).equals("ps")) return "actual\trunning\n";
                throw new CommandFailure(CommandFailure.Reason.OUTPUT_LIMIT);
            }, true, "alias", "actual").list().get(0);
            equal("RUNNING", value.status()); equal(null, value.cpuPercent());
            equal(null, value.memoryBytes()); equal("UNAVAILABLE", value.metricsStatus());
        });
        test("single instance alias resolves stats by real container", () -> {
            InstanceStatus value = service(command -> switch (command.get(1)) {
                case "inspect" -> { equal("actual", command.get(command.size() - 1)); yield "paused"; }
                case "stats" -> "actual\t0%\t0B / 1GiB";
                default -> throw new AssertionError("unexpected command");
            }, false, "alias", "actual").list().get(0);
            equal("alias", value.instanceId()); equal("actual", value.container());
            equal(0d, value.cpuPercent()); equal("STOPPED", value.status());
        });
        test("discovery keeps distinct actual names and consistent paused state", () -> {
            List<InstanceStatus> values = service(command -> command.get(1).equals("ps")
                    ? "actual\tpaused\nalias\trunning\n"
                    : "actual\t1%\t1B / 1GiB\nalias\t2%\t2B / 1GiB", true, "alias", "actual").list();
            equal(2, values.size()); equal("actual", values.get(0).instanceId()); equal("STOPPED", values.get(0).status());
            equal("alias", values.get(1).container()); equal(2d, values.get(1).cpuPercent());
        });
        test("real mode refuses missing auth token", AgentTests::realModeRequiresToken);
        test("HTTP authentication health provenance and disabled control", () -> {
            try (AgentServer server = server(config(true, false, "sample", "sample"), unexpected(), HOST)) {
                equal(401, request(server, "/api/agent/health", false).statusCode());
                equal(401, request(server, "/api/agent/health?token=" + TOKEN, false).statusCode());
                var wrongToken = HttpRequest.newBuilder(uri(server, "/api/agent/health"))
                        .header("Authorization", "Bearer test-only-wrong-token").build();
                equal(401, HTTP.send(wrongToken, HttpResponse.BodyHandlers.ofString()).statusCode());
                var health = request(server, "/api/agent/health", true);
                equal(200, health.statusCode());
                require(health.body().contains("\"protocolVersion\":\"1.1\"")
                        && health.body().contains("\"dataSource\":\"HOST\"")
                        && health.body().contains("\"readOnly\":true"), "health contract mismatch");
                var post = HttpRequest.newBuilder(uri(server, "/api/agent/tasks")).header("Authorization", "Bearer " + TOKEN)
                        .POST(HttpRequest.BodyPublishers.ofString("{\"instanceId\":\"sample\",\"action\":\"start\"}")).build();
                equal(403, HTTP.send(post, HttpResponse.BodyHandlers.ofString()).statusCode());
                var instances = request(server, "/api/agent/instances", true);
                require(instances.body().contains("\"players\":null") && instances.body().contains("\"dataSource\":\"MOCK\""),
                        "instance JSON contract mismatch");
                Files.writeString(outputDirectory.resolve("instances-contract.json"), instances.body());
                Files.writeString(outputDirectory.resolve("health-contract.json"), health.body());
            }
        });
        test("HTTP empty list differs from safe 503", () -> {
            try (AgentServer server = server(config(false, true, "sample", "sample"), (c, t, b) -> "", HOST)) {
                var response = request(server, "/api/agent/instances", true);
                equal(200, response.statusCode()); equal("[]", response.body());
            }
            try (AgentServer server = server(config(false, true, "sample", "sample"), (c, t, b) -> {
                throw new CommandFailure(CommandFailure.Reason.EXIT_FAILED);
            }, HOST)) {
                var response = request(server, "/api/agent/instances", true);
                equal(503, response.statusCode());
                equal("{\"error\":\"collection_unavailable\",\"reason\":\"EXIT_FAILED\",\"message\":\"Docker collection is unavailable\"}", response.body());
            }
        });
        test("Prometheus omits unavailable values", () -> {
            try (AgentServer server = server(config(true, false, "sample", "sample"), unexpected(), new HostMetrics(null, null, null))) {
                equal(401, request(server, "/metrics", false).statusCode());
                String text = request(server, "/metrics", true).body();
                require(!text.contains("argus_host_cpu_percent") && !text.contains("argus_host_memory_bytes"), "missing metrics emitted as numeric");
                require(text.contains("argus_host_metrics_available 0"), "missing availability indicator");
                String health = request(server, "/api/agent/health", true).body();
                require(health.contains("\"hostCpuPercent\":null") && health.contains("\"metricsStatus\":\"UNAVAILABLE\""), "null metrics lost");
            }
        });
        test("command drains output beyond pipe capacity", () -> {
            String value = new ProcessCommandRunner().run(child("large"), 10, 1048576);
            // 某些本地 JDK 会在 stderr 先打印 JAVA_TOOL_OPTIONS 提示，合并输出仍必须完整保留主体。
            require(value.endsWith("x".repeat(819200)), "large output was truncated or corrupted");
        });
        test("command output limit is explicit", () -> failure(CommandFailure.Reason.OUTPUT_LIMIT,
                () -> new ProcessCommandRunner().run(child("large"), 10, 1024)));
        test("command failures do not expose stderr", () -> {
            CommandFailure failure = failure(CommandFailure.Reason.EXIT_FAILED,
                    () -> new ProcessCommandRunner().run(child("fail"), 10, 1024));
            require(!failure.getMessage().contains("PRIVATE_TEST_MARKER"), "stderr leaked");
        });
        test("command timeout terminates child", () -> {
            Path pidFile = outputDirectory.resolve("timeout-child.pid");
            long start = System.nanoTime();
            failure(CommandFailure.Reason.TIMED_OUT,
                    () -> new ProcessCommandRunner().run(child("sleep", pidFile.toString()), 1, 1024));
            require(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 5, "timeout unbounded");
            long pid = Long.parseLong(Files.readString(pidFile));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < deadline) Thread.sleep(20);
            require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "child survived timeout");
        });
        test("output limit terminates wrapper descendants and reader", () -> processTreeCleanup("tree-limit", CommandFailure.Reason.OUTPUT_LIMIT, 10, 1024));
        test("timeout terminates wrapper descendants and reader", () -> processTreeCleanup("tree-timeout", CommandFailure.Reason.TIMED_OUT, 2, 1024));
        test("bounded HTTP queue completes burst without discarded requests", AgentTests::boundedHttpBurst);
        test("server shutdown closes pending connections", AgentTests::shutdownPendingRequests);
        test("binding config validates explicit addresses and empty overrides", AgentTests::bindAddressConfig);
        test("explicit loopback binds only loopback and logs actual address", AgentTests::loopbackBinding);
        test("failed port bind releases Inbox without explicit close", AgentTests::bindFailureReleasesInbox);
        test("partial startup failure releases port and Inbox", AgentTests::partialStartupCleanup);
        System.out.println("Agent tests: " + passed + " passed, " + failed + " failed");
        Files.writeString(outputDirectory.resolve("result.json"), Json.object("passed", passed, "failed", failed));
        if (failed != 0) System.exit(1);
    }

    private static void processTreeCleanup(String mode, CommandFailure.Reason reason, int timeoutSeconds, int maxBytes) throws Exception {
        String prefix = mode + "-" + System.nanoTime();
        Path parentPid = outputDirectory.resolve(prefix + "-parent.pid");
        Path descendantPid = outputDirectory.resolve(prefix + "-descendant.pid");
        try {
            failure(reason, () -> new ProcessCommandRunner().run(child(mode, parentPid.toString(), descendantPid.toString()), timeoutSeconds, maxBytes));
            require(Files.exists(parentPid) && Files.exists(descendantPid), "test did not create both process levels");
            for (Path pidPath : List.of(parentPid, descendantPid)) {
                long pid = Long.parseLong(Files.readString(pidPath));
                require(!ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "process tree member survived cleanup");
            }
            require(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().equals("argus-command-output")),
                    "command reader survived cleanup");
        } finally {
            // 测试失败也只清理本测试写入 PID 文件的进程，不留下模拟的 30 秒睡眠进程。
            for (Path pidPath : List.of(parentPid, descendantPid)) {
                if (Files.exists(pidPath)) ProcessHandle.of(Long.parseLong(Files.readString(pidPath))).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    private static void bindAddressConfig() throws Exception {
        Properties properties = new Properties();
        equal("0.0.0.0", AgentConfig.bindAddress(properties, Map.of()));
        equal("0.0.0.0", config(true, false, "sample", "sample").bindAddress());
        properties.setProperty("server.bind-address", "127.0.0.2");
        equal("127.0.0.2", AgentConfig.bindAddress(properties, Map.of()));
        equal("127.0.0.1", AgentConfig.bindAddress(properties, Map.of("ARGUS_AGENT_BIND_ADDRESS", "127.0.0.1")));
        equal("::1", AgentConfig.bindAddress(properties, Map.of("ARGUS_AGENT_BIND_ADDRESS", "::1")));
        for (String invalid : List.of("", " ", "localhost", "127.1", "999.0.0.1", "127.0.0.1:8090", "http://127.0.0.1", "[::1]", "bad-address")) {
            try {
                AgentConfig.bindAddress(properties, Map.of("ARGUS_AGENT_BIND_ADDRESS", invalid));
                throw new AssertionError("invalid binding accepted: " + invalid);
            } catch (IllegalArgumentException expected) {
                require(expected.getMessage().contains("server.bind-address"), "binding error not identified");
            }
        }
        properties.setProperty("server.bind-address", "");
        try { AgentConfig.bindAddress(properties, Map.of()); throw new AssertionError("empty property accepted"); }
        catch (IllegalArgumentException expected) { /* 不退回全接口。 */ }
        Path configFile = Files.createTempFile(outputDirectory.resolve("tmp"), "bind-config-", ".properties");
        Files.writeString(configFile, "server.bind-address=127.0.0.1\nexecutor.mock=true\n");
        equal("127.0.0.1", AgentConfig.load(new String[]{"--config=" + configFile}).bindAddress());
        Path unusedDirectory = outputDirectory.resolve("invalid-bind-" + System.nanoTime());
        try { boundConfig(0, "", unusedDirectory, true, 2); throw new AssertionError("constructor accepted empty binding"); }
        catch (IllegalArgumentException expected) { require(!Files.exists(unusedDirectory), "invalid config created an Inbox"); }
    }

    private static AgentConfig boundConfig(int port, String bindAddress, Path directory, boolean explicit, int httpThreads) {
        return new AgentConfig(port, "bind-test", "bind-test", TOKEN, true, "never-call-docker", "sample", "sample",
                2, 1, 4096, httpThreads, false, false, "", 1048576, 1,
                "", Set.of(), directory, 100, explicit, bindAddress);
    }

    private static void loopbackBinding() throws Exception {
        AgentConfig config = boundConfig(0, "127.0.0.1", outputDirectory.resolve("unused-bind-inbox"), false, 2);
        ByteArrayOutputStream startup = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try (AgentServer server = new AgentServer(config, new InstanceService(config, unexpected()), () -> HOST)) {
            try (PrintStream capture = new PrintStream(startup, true, java.nio.charset.StandardCharsets.UTF_8)) {
                try { System.setOut(capture); server.start(); } finally { System.setOut(original); }
            }
            equal("127.0.0.1", server.listeningAddress().getAddress().getHostAddress());
            require(server.listeningAddress().getAddress().isLoopbackAddress(), "server bound outside loopback");
            require(!server.listeningAddress().getAddress().isAnyLocalAddress(), "server silently bound wildcard");
            equal(200, request(server, "/api/agent/health", true).statusCode());
            String text = startup.toString(java.nio.charset.StandardCharsets.UTF_8);
            require(text.contains("http://127.0.0.1:" + server.listeningPort()), "startup address does not match socket");
            require(!text.contains("0.0.0.0"), "startup log still hard-codes wildcard");
        } finally { System.setOut(original); }
    }

    private static void bindFailureReleasesInbox() throws Exception {
        Path directory = Files.createTempDirectory(outputDirectory.resolve("tmp"), "bind-conflict-");
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            AgentConfig rejectedConfig = boundConfig(occupied.getLocalPort(), "127.0.0.1", directory, true, 2);
            AgentServer rejected = new AgentServer(rejectedConfig);
            try {
                try { rejected.start(); throw new AssertionError("occupied port was accepted"); }
                catch (IOException expected) { /* 失败必须自行释放 Inbox，不依赖 finally 的显式 close。 */ }
                try (AgentServer reopened = new AgentServer(boundConfig(0, "127.0.0.1", directory, true, 2))) {
                    reopened.start(); equal(200, request(reopened, "/api/agent/health", true).statusCode());
                }
                require(!occupied.isClosed(), "startup cleanup closed another owner's socket");
            } finally { rejected.close(); }
        }
    }

    private static void partialStartupCleanup() throws Exception {
        Path directory = Files.createTempDirectory(outputDirectory.resolve("tmp"), "bind-partial-");
        int port;
        try (ServerSocket reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)); port = reservation.getLocalPort();
        }
        // HTTP 初始化失败仍须释放 Inbox；执行器应在占用端口之前构造完成。
        AgentServer rejected = new AgentServer(boundConfig(port, "127.0.0.1", directory, true, 0));
        try {
            try { rejected.start(); throw new AssertionError("invalid executor accepted"); }
            catch (IllegalArgumentException expected) { /* 端口与 Inbox 都应释放。 */ }
            try (ServerSocket rebound = new ServerSocket()) {
                rebound.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
                try (AgentServer reopened = new AgentServer(boundConfig(0, "127.0.0.1", directory, true, 2))) {
                    reopened.start(); equal(200, request(reopened, "/api/agent/health", true).statusCode());
                }
            }
        } finally { rejected.close(); }
    }

    private static void realModeRequiresToken() throws Exception {
        ProcessBuilder builder = new ProcessBuilder(child("require-token"));
        builder.environment().keySet().removeIf(key -> key.startsWith("ARGUS_AGENT_"));
        builder.environment().put("ARGUS_AGENT_MOCK", "false");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        require(process.waitFor(5, TimeUnit.SECONDS), "config child timed out");
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        equal(0, process.exitValue()); require(result.contains("AUTH_GUARD_OK"), "auth guard did not reject missing token");
    }

    private static void boundedHttpBurst() throws Exception {
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        try (AgentServer server = new AgentServer(config(true, false, "sample", "sample"),
                new InstanceService(config(true, false, "sample", "sample"), unexpected()), () -> {
                    int now = active.incrementAndGet(); peak.accumulateAndGet(now, Math::max);
                    try { Thread.sleep(80); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    finally { active.decrementAndGet(); }
                    return HOST;
                })) {
            server.start();
            List<CompletableFuture<HttpResponse<String>>> calls = requests(server, 12);
            for (var call : calls) equal(200, call.get(10, TimeUnit.SECONDS).statusCode());
            require(peak.get() <= 3, "worker count exceeded configured workers plus dispatcher");
        }
    }

    private static void shutdownPendingRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        AgentServer server = new AgentServer(config(true, false, "sample", "sample"),
                new InstanceService(config(true, false, "sample", "sample"), unexpected()), () -> {
                    entered.countDown();
                    try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    return HOST;
                });
        try {
            server.start();
            var calls = requests(server, 8);
            require(entered.await(5, TimeUnit.SECONDS), "requests did not reach server");
            server.close();
            // 客户端超时设置为 20 秒；此处要求 4 秒内收到响应/连接关闭，不靠客户端超时掩盖挂起。
            CompletableFuture.allOf(calls.stream().map(call -> call.handle((value, failure) -> null))
                    .toArray(CompletableFuture[]::new)).get(4, TimeUnit.SECONDS);
        } finally { server.close(); }
    }

    private static List<CompletableFuture<HttpResponse<String>>> requests(AgentServer server, int count) {
        List<CompletableFuture<HttpResponse<String>>> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var request = HttpRequest.newBuilder(uri(server, "/api/agent/health")).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + TOKEN).build();
            result.add(HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
        }
        return result;
    }

    private static InstanceStatus sample(String cpu, String memory) {
        return service(command -> command.get(1).equals("ps") ? "actual\trunning\n"
                : "actual\t" + cpu + "\t" + memory + " / 1GiB", true, "actual", "actual").list().get(0);
    }

    private static InstanceService service(Output output, boolean discovery, String id, String container) {
        AgentConfig config = config(false, discovery, id, container);
        return new InstanceService(config, (command, timeout, maxBytes) -> output.read(command));
    }

    private static AgentConfig config(boolean mock, boolean discovery, String id, String container) {
        return new AgentConfig(0, "test-node", "test-node", TOKEN, mock, "not-a-real-docker-executable", id, container,
                5, 1, 4096, 2, false, discovery, "localhost", 1048576, 1);
    }

    private static CommandRunner unexpected() {
        return (command, timeout, maxBytes) -> { throw new AssertionError("mock unexpectedly invoked command runner"); };
    }

    private static AgentServer server(AgentConfig config, CommandRunner runner, HostMetrics host) throws Exception {
        AgentServer server = new AgentServer(config, new InstanceService(config, runner), () -> host);
        server.start(); return server;
    }

    private static URI uri(AgentServer server, String path) { return URI.create("http://127.0.0.1:" + server.listeningPort() + path); }
    private static HttpResponse<String> request(AgentServer server, String path, boolean authorized) throws Exception {
        var builder = HttpRequest.newBuilder(uri(server, path)).timeout(Duration.ofSeconds(5));
        if (authorized) builder.header("Authorization", "Bearer " + TOKEN);
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> child(String... args) {
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.io.tmpdir=" + outputDirectory.resolve("tmp"), "-cp", System.getProperty("java.class.path"),
                AgentTestChild.class.getName()));
        command.addAll(List.of(args)); return command;
    }

    private static CommandFailure failure(CommandFailure.Reason reason, Check action) throws Exception {
        try { action.run(); } catch (CommandFailure failure) { equal(reason, failure.reason()); return failure; }
        throw new AssertionError("expected command failure " + reason);
    }
    private static void test(String name, Check action) {
        try { action.run(); passed++; System.out.println("[PASS] " + name); }
        catch (Throwable failure) { failed++; System.out.println("[FAIL] " + name + ": " + failure); }
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Check { void run() throws Exception; }
    @FunctionalInterface private interface Output { String read(List<String> command); }
}
