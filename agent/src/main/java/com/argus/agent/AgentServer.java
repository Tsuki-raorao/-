package com.argus.agent;

import com.argus.agent.model.InstanceStatus;
import com.argus.agent.model.NodeInfo;
import com.argus.agent.model.TaskView;
import com.argus.agent.service.InstanceService;
import com.argus.agent.service.TaskService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/** 基于 JDK HttpServer 的 REST API；Agent 刻意不引入框架依赖。 */
public final class AgentServer {
    private final AgentConfig config;
    private final InstanceService instances;
    private final TaskService tasks;
    private final long startedAt = System.currentTimeMillis();
    private HttpServer server;
    private ExecutorService httpExecutor;
    public AgentServer(AgentConfig config) { this.config = config; this.instances = new InstanceService(config); this.tasks = new TaskService(instances, config.maxConcurrentTasks()); }
    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.port()), 0);
        httpExecutor = Executors.newFixedThreadPool(config.maxHttpThreads());
        server.createContext("/", this::handle); server.setExecutor(httpExecutor); server.start();
        System.out.println("Argus Agent " + config.nodeId() + " listening on http://0.0.0.0:" + config.port() + " (mock=" + config.mock() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(1); if (httpExecutor != null) httpExecutor.shutdownNow(); tasks.close(); }));
    }
    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) { send(exchange, 401, Json.object("error", "unauthorized")); return; }
            String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
            if (path.equals("/api/agent/health") && method.equals("GET")) {
                HostMetrics metrics = HostMetrics.read();
                send(exchange, 200, Json.object("status", "ONLINE", "nodeId", config.nodeId(), "version", "0.1.0", "startedAt", startedAt,
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
        } catch (IllegalArgumentException e) { send(exchange, 400, Json.object("error", e.getMessage())); }
        catch (RejectedExecutionException e) { send(exchange, 429, Json.object("error", "agent_busy", "message", "task queue is full; retry later")); }
        catch (Exception e) { send(exchange, 500, Json.object("error", "agent_error", "message", e.getMessage())); }
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
    private String instanceJson(InstanceStatus x) { return Json.object("instanceId", x.instanceId(), "name", x.name(), "container", x.container(), "status", x.status(), "cpuPercent", x.cpuPercent(), "memoryBytes", x.memoryBytes(), "players", x.players(), "checkedAt", x.checkedAt()); }
    private String taskJson(TaskView x) { return Json.object("taskId", x.taskId(), "instanceId", x.instanceId(), "action", x.action(), "status", x.status(), "message", x.message(), "createdAt", x.createdAt(), "finishedAt", x.finishedAt()); }
    private int query(URI uri, String key, int fallback) { String raw = uri.getRawQuery(); if (raw == null) return fallback; for (String p : raw.split("&")) { String[] kv = p.split("=", 2); if (kv.length == 2 && kv[0].equals(key)) try { return Integer.parseInt(kv[1]); } catch (NumberFormatException ignored) { } } return fallback; }
    private void send(HttpExchange e, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(code, bytes.length); try (var out = e.getResponseBody()) { out.write(bytes); } }
    /** 输出 Prometheus 0.0.4 文本格式；接口仍复用 Agent 令牌认证，不额外开放端口。 */
    private String metricsText() {
        HostMetrics host = HostMetrics.read();
        StringBuilder out = new StringBuilder(2048);
        metric(out, "argus_agent_up", "Agent 是否正常提供采集服务", 1, null);
        metric(out, "argus_agent_info", "Agent 版本信息", 1,
                "node_id=\"" + escapeLabel(config.nodeId()) + "\",agent_version=\"0.1.0\"");
        metric(out, "argus_host_cpu_percent", "主机 CPU 使用率百分比", host.cpuPercent(), null);
        metric(out, "argus_host_memory_bytes", "主机已使用内存字节数", host.memoryBytes(), null);
        metric(out, "argus_host_memory_total_bytes", "主机内存总字节数", host.memoryTotalBytes(), null);
        metric(out, "argus_host_memory_percent", "主机内存使用率百分比", host.memoryPercent(), null);
        metric(out, "argus_host_metrics_available", "主机指标是否可用", host.memoryTotalBytes() > 0 ? 1 : 0, null);
        return out.toString();
    }
    private void metric(StringBuilder out, String name, String help, double value, String labels) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n").append(name);
        if (labels != null && !labels.isBlank()) out.append('{').append(labels).append('}');
        out.append(' ').append(value).append('\n');
    }
    private String escapeLabel(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private void sendText(HttpExchange e, int code, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8"); e.getResponseHeaders().set("Cache-Control", "no-store"); e.getResponseHeaders().set("X-Content-Type-Options", "nosniff"); e.sendResponseHeaders(code, bytes.length); try (var out = e.getResponseBody()) { out.write(bytes); } }
}
