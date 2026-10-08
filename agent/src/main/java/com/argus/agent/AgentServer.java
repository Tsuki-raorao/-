package com.argus.agent;

import com.argus.agent.model.InstanceStatus;
import com.argus.agent.model.NodeInfo;
import com.argus.agent.model.TaskView;
import com.argus.agent.service.InstanceService;
import com.argus.agent.service.TaskService;
import com.argus.agent.command.CommandFailure;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** 基于 JDK HttpServer 的 REST API；Agent 刻意不引入框架依赖。 */
public final class AgentServer implements AutoCloseable {
    private final AgentConfig config;
    private final InstanceService instances;
    private final TaskService tasks;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer server;
    private ExecutorService httpExecutor;
    private final Supplier<HostMetrics> hostMetrics;
    private Thread shutdownHook;
    public AgentServer(AgentConfig config) { this(config, new InstanceService(config), HostMetrics::read); }
    AgentServer(AgentConfig config, InstanceService instances, Supplier<HostMetrics> hostMetrics) {
        this.config = config;
        this.instances = instances;
        this.hostMetrics = hostMetrics;
        this.tasks = new TaskService(instances, config.maxConcurrentTasks());
    }
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        // 队列满时由提交请求的调度线程执行，让接收侧背压；不静默丢弃已接受的连接。
        // 这限制了待执行队列，但慢客户端仍可能占用线程，不等同于完整流量防护。
        httpExecutor = new ThreadPoolExecutor(config.maxHttpThreads(), config.maxHttpThreads(), 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.maxHttpQueueSize()), new ThreadPoolExecutor.CallerRunsPolicy());
        server.createContext("/", this::handle); server.setExecutor(httpExecutor); server.start();
        System.out.println("Argus Agent " + config.nodeId() + " listening on http://0.0.0.0:" + server.getAddress().getPort() + " (mock=" + config.mock() + ")");
        shutdownHook = new Thread(this::close, "argus-agent-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }
    int listeningPort() { return server.getAddress().getPort(); }
    @Override public void close() {
        if (server != null) server.stop(0);
        if (httpExecutor != null) httpExecutor.shutdownNow();
        tasks.close();
        if (shutdownHook != null && Thread.currentThread() != shutdownHook) {
            try { Runtime.getRuntime().removeShutdownHook(shutdownHook); } catch (IllegalStateException ignored) { }
        }
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) { send(exchange, 401, Json.object("error", "unauthorized")); return; }
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            if (path.equals("/api/agent/health") && method.equals("GET")) {
                HostMetrics metrics = hostMetrics.get();
                send(exchange, 200, Json.object("status", "ONLINE", "nodeId", config.nodeId(), "version", "0.1.0", "startedAt", startedAt,
                        "protocolVersion", "1.1", "dataSource", "HOST", "sampledAt", Instant.now().toString(), "metricsStatus", metrics.metricsStatus(),
                        "readOnly", !config.controlEnabled(), "hostCpuPercent", metrics.cpuPercent(),
                        "hostMemoryBytes", metrics.memoryBytes(), "hostMemoryTotalBytes", metrics.memoryTotalBytes(),
                        "hostMemoryPercent", metrics.memoryPercent(), "capabilities", capabilities())); return;
            }
            if (path.equals("/metrics") && method.equals("GET")) { sendText(exchange, 200, metricsText()); return; }
            if (path.equals("/api/agent/register") && method.equals("POST")) { send(exchange, 200, nodeJson()); return; }
            if (path.equals("/api/agent/heartbeat") && method.equals("POST")) { send(exchange, 200, Json.object("nodeId", config.nodeId(), "status", "ONLINE", "timestamp", Instant.now().toString())); return; }
            if (path.equals("/api/agent/instances") && method.equals("GET")) { send(exchange, 200, instancesJson(instances.list())); return; }
            if (path.equals("/api/agent/tasks") && method.equals("POST")) {
                if (!config.controlEnabled()) { send(exchange, 403, Json.object("error", "control_disabled", "message", "remote control is disabled by configuration")); return; }
                createTask(exchange); return;
            }
            if (path.startsWith("/api/agent/tasks/") && method.equals("GET")) { task(exchange, path.substring("/api/agent/tasks/".length())); return; }
            if (path.startsWith("/api/agent/instances/") && path.endsWith("/logs") && method.equals("GET")) { logs(exchange, path); return; }
            send(exchange, 404, Json.object("error", "not_found"));
        } catch (CommandFailure e) {
            send(exchange, 503, Json.object("error", "collection_unavailable", "reason", e.reason().name(),
                    "message", "Docker collection is unavailable"));
        } catch (IllegalArgumentException e) { send(exchange, 400, Json.object("error", e.getMessage())); }
        catch (RejectedExecutionException e) { send(exchange, 429, Json.object("error", "agent_busy", "message", "task queue is full; retry later")); }
        catch (Exception e) { send(exchange, 500, Json.object("error", "agent_error", "message", "Agent request failed")); }
    }
    private void createTask(HttpExchange exchange) throws IOException {
        Map<String, String> body = Json.flatObject(read(exchange));
        try { TaskView task = tasks.submit(body.get("instanceId"), body.get("action")); send(exchange, 202, taskJson(task)); }
        catch (IllegalArgumentException e) { send(exchange, 400, Json.object("error", e.getMessage())); }
    }
    private void task(HttpExchange exchange, String id) throws IOException {
        TaskView task = tasks.get(id); if (task == null) { send(exchange, 404, Json.object("error", "task_not_found")); return; } send(exchange, 200, taskJson(task));
    }
    private void logs(HttpExchange exchange, String path) throws Exception {
        String id = path.substring("/api/agent/instances/".length(), path.length() - "/logs".length());
        int limit = query(exchange.getRequestURI(), "limit", 100); send(exchange, 200, Json.array(instances.logs(id, limit)));
    }
    private String read(HttpExchange e) throws IOException {
        String length = e.getRequestHeaders().getFirst("Content-Length");
        if (length != null) {
            try { if (Long.parseLong(length) > config.maxRequestBytes()) throw new IllegalArgumentException("request body is too large"); }
            catch (NumberFormatException ignored) { /* chunked requests are checked while reading */ }
        }
        // 分块读取并限制总大小，不能直接使用 readAllBytes() 接受无限输入。
        try (var in = e.getRequestBody(); var out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int total = 0; int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > config.maxRequestBytes()) throw new IllegalArgumentException("request body is too large");
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
    private boolean authorized(HttpExchange e) { return config.authToken().isBlank() || ("Bearer " + config.authToken()).equals(e.getRequestHeaders().getFirst("Authorization")); }
    private String nodeJson() { NodeInfo n = new NodeInfo(config.nodeId(), config.nodeName(), "ONLINE", "0.1.0", advertisedHost(), config.port(), startedAt); return Json.object("nodeId", n.nodeId(), "name", n.name(), "status", n.status(), "agentVersion", n.agentVersion(), "host", n.host(), "port", n.port(), "startedAt", n.startedAtEpochMs(), "capabilities", capabilities()); }
    private String advertisedHost() { return config.advertisedHost().isBlank() ? "0.0.0.0" : config.advertisedHost(); }
    /** 当前 Agent 暴露的能力清单；列表是只读能力，控制能力仍由 control.enabled 单独保护。 */
    private Json.Raw capabilities() { return Json.raw("[\"read_health\",\"read_instances\",\"read_logs\",\"read_metrics\"]"); }
    private String instancesJson(List<InstanceStatus> values) { List<String> json = new ArrayList<>(); for (InstanceStatus x : values) json.add(instanceJson(x)); return "[" + String.join(",", json) + "]"; }
    private String instanceJson(InstanceStatus x) { return Json.object("instanceId", x.instanceId(), "name", x.name(), "container", x.container(), "status", x.status(), "cpuPercent", x.cpuPercent(), "memoryBytes", x.memoryBytes(), "players", x.players(), "checkedAt", x.checkedAt(), "dataSource", x.dataSource(), "sampledAt", x.sampledAt(), "metricsStatus", x.metricsStatus()); }
    private String taskJson(TaskView x) { return Json.object("taskId", x.taskId(), "instanceId", x.instanceId(), "action", x.action(), "status", x.status(), "message", x.message(), "createdAt", x.createdAt(), "finishedAt", x.finishedAt()); }
    private int query(URI uri, String key, int fallback) { String raw = uri.getRawQuery(); if (raw == null) return fallback; for (String p : raw.split("&")) { String[] kv = p.split("=", 2); if (kv.length == 2 && kv[0].equals(key)) try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) { } } return fallback; }
    private void send(HttpExchange e, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(code, bytes.length); try (var out = e.getResponseBody()) { out.write(bytes); } }
    /** 输出 Prometheus 0.0.4 文本格式；接口仍复用 Agent 令牌认证，不额外开放端口。 */
    private String metricsText() {
        HostMetrics host = hostMetrics.get();
        StringBuilder out = new StringBuilder(2048);
        metric(out, "argus_agent_up", "Agent 是否正常提供采集服务", 1, null);
        metric(out, "argus_agent_info", "Agent 版本信息", 1,
                "node_id=\"" + escapeLabel(config.nodeId()) + "\",agent_version=\"0.1.0\"");
        metric(out, "argus_host_cpu_percent", "主机 CPU 使用率百分比", host.cpuPercent(), null);
        metric(out, "argus_host_memory_bytes", "主机已使用内存字节数", host.memoryBytes(), null);
        metric(out, "argus_host_memory_total_bytes", "主机内存总字节数", host.memoryTotalBytes(), null);
        metric(out, "argus_host_memory_percent", "主机内存使用率百分比", host.memoryPercent(), null);
        metric(out, "argus_host_metrics_available", "主机全部指标是否可用", "AVAILABLE".equals(host.metricsStatus()) ? 1 : 0, null);
        metric(out, "argus_host_cpu_available", "主机 CPU 指标是否可用", host.cpuPercent() != null ? 1 : 0, null);
        metric(out, "argus_host_memory_available", "主机内存指标是否可用", host.memoryBytes() != null && host.memoryTotalBytes() != null ? 1 : 0, null);
        return out.toString();
    }
    private void metric(StringBuilder out, String name, String help, Number value, String labels) {
        // 缺失指标省略；availability 是采集状态本身，其 0/1 有明确含义。
        if (value == null || !Double.isFinite(value.doubleValue())) return;
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n").append(name);
        if (labels != null && !labels.isBlank()) out.append('{').append(labels).append('}');
        out.append(' ').append(value).append('\n');
    }
    private String escapeLabel(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private void sendText(HttpExchange e, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8"); e.getResponseHeaders().set("Cache-Control", "no-store"); e.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); e.sendResponseHeaders(code, bytes.length); try (var out = e.getResponseBody()) { out.write(bytes); } }
}
