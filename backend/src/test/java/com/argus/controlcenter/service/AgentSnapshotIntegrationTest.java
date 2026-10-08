package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** 使用两个真实本机 HTTP 服务，验证隔离身份、失败时间、只读日志和完整快照边界。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:snapshot-tests;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "argus.demo-data.enabled=false", "argus.agent.enabled=true", "argus.agent.auth-token=test-token",
        "argus.agent.allowed-hosts=127.0.0.1", "argus.agent.sync-initial-delay-ms=86400000", "argus.security.read-only=true"})
@ActiveProfiles("test")
class AgentSnapshotIntegrationTest {
    @Autowired AgentSnapshotSyncService sync;
    @Autowired NodeService nodeService;
    @Autowired NodeRepository nodes;
    @Autowired InstanceRepository instances;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    final List<Fixture> fixtures = new ArrayList<>();

    @BeforeEach void cleanDatabase() {
        jdbc.update("DELETE FROM logs"); jdbc.update("DELETE FROM tasks");
        jdbc.update("DELETE FROM instances"); jdbc.update("DELETE FROM nodes");
    }
    @AfterEach void stopAgents() { fixtures.forEach(f -> f.server.stop(0)); }

    @Test void sameNameAcrossTwoNodesKeepsStableIdsAndRoutesLogsWithoutControl() throws Exception {
        Fixture a = agent("node-a", 11);
        Fixture b = agent("node-b", 22);
        sync.syncAll();
        Instance first = instance("node-a"); Instance second = instance("node-b");
        assertThat(first.getId()).isNotEqualTo(second.getId()).isNotEqualTo("mc01");
        assertThat(UUID.fromString(first.getId())).isNotNull();
        assertThat(first.getCpuPercent()).isEqualTo(11);
        assertThat(first.getLastSeenAt()).isEqualTo(nodes.findById("node-a").orElseThrow().getLastSuccessfulSyncAt());
        assertThat(second.getCpuPercent()).isEqualTo(22);
        sync.syncNode("node-b"); sync.syncNode("node-a"); sync.syncAll();
        assertThat(instance("node-a").getId()).isEqualTo(first.getId());
        assertThat(instance("node-b").getId()).isEqualTo(second.getId());
        assertThat(instances.count()).isEqualTo(2);
        for (Instance value : List.of(first, second)) {
            ResponseEntity<Map> response = http.getForEntity("/api/instances/" + value.getId() + "/logs?limit=4", Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map data = (Map) response.getBody().get("data");
            assertThat(data).containsEntry("instanceId", value.getId()).containsEntry("nodeId", value.getNodeId())
                    .containsEntry("agentInstanceId", "mc01").containsEntry("lines", List.of(value.getNodeId() + " log"));
            assertThat(Instant.parse((String) data.get("collectedAt"))).isNotNull();
        }
        ResponseEntity<Map> denied = http.postForEntity("/api/instances/" + first.getId() + "/actions", Map.of("action", "STOP"), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(a.writes.get() + b.writes.get()).isZero();
        assertThat(a.unauthenticated.get() + b.unauthenticated.get()).isZero();
    }

    @Test void failedHealthKeepsSuccessTimesAndMetrics() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Node before = nodes.findById("node-a").orElseThrow();
        Instance old = instance("node-a");
        a.healthCode = 503; a.health = "secret remote detail";
        sync.syncAll();
        Node after = nodes.findById("node-a").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NodeStatus.OFFLINE);
        assertThat(after.getSyncStatus()).isEqualTo(SyncStatus.FAILED);
        assertThat(after.getSyncErrorCode()).isEqualTo("HEALTH_REQUEST_FAILED");
        assertThat(after.getLastHeartbeat()).isEqualTo(before.getLastHeartbeat());
        assertThat(after.getLastSuccessfulSyncAt()).isEqualTo(before.getLastSuccessfulSyncAt());
        assertThat(after.getLastCheckedAt()).isAfterOrEqualTo(before.getLastCheckedAt());
        assertThat(after.getCpuPercent()).isEqualTo(before.getCpuPercent());
        assertThat(after.getSampledAt()).isEqualTo(before.getSampledAt());
        assertThat(instance("node-a").getLastSeenAt()).isEqualTo(old.getLastSeenAt());
    }

    @Test void healthSuccessWithDiscoveryFailureIsPartial() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Node before = nodes.findById("node-a").orElseThrow();
        Instance old = instance("node-a");
        a.instancesCode = 503; a.snapshot = "[]"; sync.syncAll();
        Node after = nodes.findById("node-a").orElseThrow();
        assertThat(after.getStatus()).isEqualTo(NodeStatus.ONLINE);
        assertThat(after.getSyncStatus()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(after.getSyncErrorCode()).isEqualTo("INSTANCES_REQUEST_FAILED");
        assertThat(after.getLastHeartbeat()).isAfterOrEqualTo(before.getLastHeartbeat());
        assertThat(after.getLastSuccessfulSyncAt()).isEqualTo(before.getLastSuccessfulSyncAt());
        assertThat(instance("node-a").getLastSeenAt()).isEqualTo(old.getLastSeenAt());
    }

    @ParameterizedTest
    @ValueSource(strings = {"object", "duplicate", "partial", "broken-json", "negative"})
    void invalidListDoesNotPartiallyOverwrite(String kind) throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Instance before = instance("node-a");
        Node previous = nodes.findById("node-a").orElseThrow();
        String changed = item("mc01", "DOCKER", "AVAILABLE", "99", "2048", "null");
        a.snapshot = switch (kind) {
            case "object" -> "{}";
            case "duplicate" -> "[" + changed + "," + changed + "]";
            case "partial" -> "[" + changed + ",{\"instanceId\":\"bad\"}]";
            case "negative" -> "[" + changed + "," + item("bad", "DOCKER", "AVAILABLE", "-1", "5", "null") + "]";
            default -> "[broken";
        };
        sync.syncAll();
        assertThat(instance("node-a").getCpuPercent()).isEqualTo(before.getCpuPercent());
        assertThat(instance("node-a").getLastSeenAt()).isEqualTo(before.getLastSeenAt());
        assertThat(instances.count()).isEqualTo(1);
        assertThat(nodes.findById("node-a").orElseThrow().getSyncStatus()).isEqualTo(SyncStatus.PARTIAL);
        assertThat(nodes.findById("node-a").orElseThrow().getLastSuccessfulSyncAt()).isEqualTo(previous.getLastSuccessfulSyncAt());
    }

    @Test void completeEmptyListPreservesHistoricalInstancesWithoutRefreshingThem() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Instance before = instance("node-a");
        a.snapshot = "[]"; sync.syncAll();
        assertThat(instances.count()).isEqualTo(1);
        assertThat(instance("node-a").getLastSeenAt()).isEqualTo(before.getLastSeenAt());
        Node after = nodes.findById("node-a").orElseThrow();
        assertThat(after.getSyncStatus()).isEqualTo(SyncStatus.OK);
        assertThat(after.getLastSuccessfulSyncAt()).isAfterOrEqualTo(before.getLastSeenAt());
    }

    @Test void nullZeroMockAndLegacyRemainDistinct() throws Exception {
        Fixture a = agent("node-a", 0); sync.syncAll();
        assertThat(instance("node-a").getCpuPercent()).isZero();
        assertThat(instance("node-a").getPlayerCount()).isNull();
        assertThat(instance("node-a").getMetricsStatus()).isEqualTo(MetricsStatus.AVAILABLE);
        a.snapshot = "[" + item("mc01", "DOCKER", "PARTIAL", "null", "2048", "null") + "]"; sync.syncAll();
        assertThat(instance("node-a").getCpuPercent()).isNull();
        assertThat(instance("node-a").getMemoryBytes()).isEqualTo(2048);
        a.snapshot = "[" + item("mc01", "DOCKER", "UNAVAILABLE", "null", "null", "null") + "]"; sync.syncAll();
        assertThat(instance("node-a").getMemoryBytes()).isNull();
        a.snapshot = "[" + item("mc01", "MOCK", "AVAILABLE", "12", "2048", "null") + "]"; sync.syncAll();
        Instance mock = instance("node-a");
        assertThat(mock.getDataSource()).isEqualTo("MOCK");
        a.health = "{\"status\":\"ONLINE\",\"hostCpuPercent\":0,\"hostMemoryBytes\":0}";
        a.snapshot = "[{\"instanceId\":\"mc01\",\"status\":\"RUNNING\",\"cpuPercent\":0,\"memoryBytes\":0,\"players\":0}]";
        sync.syncAll();
        Instance legacy = instance("node-a");
        assertThat(legacy.getDataSource()).isEqualTo("LEGACY");
        assertThat(legacy.getMetricsStatus()).isEqualTo(MetricsStatus.UNKNOWN);
        assertThat(legacy.getCpuPercent()).isEqualTo(mock.getCpuPercent());
        assertThat(legacy.getSampledAt()).isEqualTo(mock.getSampledAt());
        assertThat(nodes.findById("node-a").orElseThrow().getMetricsStatus()).isEqualTo(MetricsStatus.UNKNOWN);
    }

    @Test void invalidHealthDoesNotAdvanceHeartbeat() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Node before = nodes.findById("node-a").orElseThrow();
        a.health = "{}"; sync.syncAll();
        Node after = nodes.findById("node-a").orElseThrow();
        assertThat(after.getSyncErrorCode()).isEqualTo("INVALID_HEALTH");
        assertThat(after.getLastHeartbeat()).isEqualTo(before.getLastHeartbeat());
    }

    @Test void partialHostMetricsAreUsableEvidenceWithoutInventingMissingMemory() throws Exception {
        Fixture a = agent("node-a", 11);
        a.health = "{\"status\":\"ONLINE\",\"protocolVersion\":\"1.1\",\"dataSource\":\"HOST\",\"sampledAt\":\"" + Instant.now()
                + "\",\"metricsStatus\":\"PARTIAL\",\"hostCpuPercent\":0,\"hostMemoryBytes\":null,\"hostMemoryTotalBytes\":4096}";
        sync.syncAll();
        Node node = nodes.findById("node-a").orElseThrow();
        assertThat(node.getSyncStatus()).isEqualTo(SyncStatus.OK);
        assertThat(node.getMetricsStatus()).isEqualTo(MetricsStatus.PARTIAL);
        assertThat(node.getCpuPercent()).isZero();
        assertThat(node.getMemoryBytes()).isNull();
        assertThat(node.getMemoryTotalBytes()).isEqualTo(4096);
        assertThat(node.getLastHeartbeat()).isNotNull();
        assertThat(node.getSampledAt()).isNotNull();
    }

    @Test void logErrorsAreNotEmptySuccess() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        String path = "/api/instances/" + instance("node-a").getId() + "/logs";
        a.logsCode = 503;
        assertThat(http.getForEntity(path, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        a.logsCode = 200; a.logs = "[4]";
        assertThat(http.getForEntity(path, Map.class).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        a.logs = "[]";
        assertThat(http.getForEntity(path, Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test void persistenceFailureRollsBackWholeListAndSuccessTimestamp() throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        Instance before = instance("node-a");
        Node nodeBefore = nodes.findById("node-a").orElseThrow();
        jdbc.execute("ALTER TABLE instances ADD CONSTRAINT test_cpu_ceiling CHECK(cpu_percent <= 50)");
        try {
            a.snapshot = "[" + item("mc01", "DOCKER", "AVAILABLE", "22", "1024", "null") + ","
                    + item("zz-bad", "DOCKER", "AVAILABLE", "99", "1024", "null") + "]";
            sync.syncAll();
            assertThat(instance("node-a").getCpuPercent()).isEqualTo(before.getCpuPercent());
            assertThat(instance("node-a").getLastSeenAt()).isEqualTo(before.getLastSeenAt());
            assertThat(instances.count()).isEqualTo(1);
            Node after = nodes.findById("node-a").orElseThrow();
            assertThat(after.getSyncErrorCode()).isEqualTo("PERSISTENCE_FAILED");
            assertThat(after.getLastSuccessfulSyncAt()).isEqualTo(nodeBefore.getLastSuccessfulSyncAt());
        } finally { jdbc.execute("ALTER TABLE instances DROP CONSTRAINT test_cpu_ceiling"); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"health", "instances", "health-failure", "instances-failure"})
    void deletingNodeDuringNetworkWaitCannotResurrectIt(String stage) throws Exception {
        Fixture a = agent("node-a", 11); sync.syncAll();
        a.blockedPath = stage.startsWith("health") ? "/api/agent/health" : "/api/agent/instances";
        a.entered = new CountDownLatch(1); a.release = new CountDownLatch(1);
        if (stage.equals("health-failure")) a.healthCode = 503;
        if (stage.equals("instances-failure")) a.instancesCode = 503;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<?> pending = worker.submit(() -> sync.syncNode("node-a"));
        try {
            assertThat(a.entered.await(5, TimeUnit.SECONDS)).isTrue();
            nodeService.delete("node-a");
            assertThat(nodes.findById("node-a")).isEmpty();
            a.release.countDown();
            pending.get(10, TimeUnit.SECONDS);
            assertThat(nodes.findById("node-a")).isEmpty();
            assertThat(instances.count()).isZero();
        } finally { a.release.countDown(); worker.shutdownNow(); }
    }

    private Instance instance(String node) { return instances.findByNodeAndAgentId(node, "mc01").orElseThrow(); }
    private Fixture agent(String id, int cpu) throws Exception {
        Fixture fixture = new Fixture(id, cpu); fixtures.add(fixture);
        nodes.save(new Node(id, id, "127.0.0.1:" + fixture.server.getAddress().getPort(), NodeStatus.UNKNOWN, null));
        return fixture;
    }
    static String item(String id, String source, String status, String cpu, String memory, String players) {
        return "{\"instanceId\":\"" + id + "\",\"status\":\"RUNNING\",\"version\":\"1.0\",\"dataSource\":\"" + source
                + "\",\"sampledAt\":\"" + Instant.now() + "\",\"metricsStatus\":\"" + status + "\",\"cpuPercent\":" + cpu
                + ",\"memoryBytes\":" + memory + ",\"players\":" + players + "}";
    }
    static class Fixture {
        final HttpServer server;
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger unauthenticated = new AtomicInteger();
        volatile String health = "{\"status\":\"ONLINE\",\"protocolVersion\":\"1.1\",\"dataSource\":\"HOST\",\"sampledAt\":\"" + Instant.now()
                + "\",\"metricsStatus\":\"AVAILABLE\",\"hostCpuPercent\":10,\"hostMemoryBytes\":1024,\"hostMemoryTotalBytes\":4096}";
        volatile String snapshot;
        volatile String logs;
        volatile int healthCode = 200, instancesCode = 200, logsCode = 200;
        volatile String blockedPath;
        volatile CountDownLatch entered, release;
        Fixture(String id, int cpu) throws Exception {
            snapshot = "[" + item("mc01", "DOCKER", "AVAILABLE", String.valueOf(cpu), "1024", "null") + "]";
            logs = "[\"" + id + " log\"]";
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                if (!exchange.getRequestMethod().equals("GET")) writes.incrementAndGet();
                if (!"Bearer test-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) unauthenticated.incrementAndGet();
                String path = exchange.getRequestURI().getPath();
                if (path.equals(blockedPath)) {
                    entered.countDown();
                    try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("test synchronization timed out"); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException("test interrupted"); }
                }
                boolean isHealth = path.equals("/api/agent/health");
                boolean isLogs = path.equals("/api/agent/instances/mc01/logs");
                byte[] body = (isHealth ? health : isLogs ? logs : snapshot).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(isHealth ? healthCode : isLogs ? logsCode : instancesCode, body.length);
                try (var stream = exchange.getResponseBody()) { stream.write(body); }
            });
            server.start();
        }
    }
}
