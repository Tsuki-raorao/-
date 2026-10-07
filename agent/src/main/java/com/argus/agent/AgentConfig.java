package com.argus.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.UUID;

/** Agent 不可变配置；配置文件可选，环境变量优先覆盖文件值。 */
public record AgentConfig(int port, String nodeId, String nodeName, String authToken,
                          boolean mock, String dockerExecutable, String defaultInstanceId,
                          String defaultContainer, int commandTimeoutSeconds,
                          int maxConcurrentTasks, int maxRequestBytes, int maxHttpThreads,
                          boolean controlEnabled, boolean discoveryEnabled, String advertisedHost) {
    public static AgentConfig load(String[] args) throws IOException {
        Properties p = new Properties();
        String configPath = System.getenv().getOrDefault("ARGUS_AGENT_CONFIG", "agent/config/agent.properties");
        for (String arg : args) if (arg.startsWith("--config=")) configPath = arg.substring("--config=".length());
        Path path = Path.of(configPath);
        if (Files.isRegularFile(path)) try (InputStream in = Files.newInputStream(path)) { p.load(in); }
        String id = envOr(p, "ARGUS_AGENT_NODE_ID", "node.id", "node-" + UUID.randomUUID());
        String name = envOr(p, "ARGUS_AGENT_NODE_NAME", "node.name", id);
        int port = positiveInt(envOr(p, "ARGUS_AGENT_PORT", "server.port", "8090"), "server.port", 1, 65535);
        boolean mock = Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_MOCK", "executor.mock", "true"));
        int maxTasks = positiveInt(envOr(p, "ARGUS_AGENT_MAX_TASKS", "executor.max-concurrent-tasks", "4"),
                "executor.max-concurrent-tasks", 1, 64);
        int maxRequestBytes = positiveInt(envOr(p, "ARGUS_AGENT_MAX_REQUEST_BYTES", "server.max-request-bytes", "65536"),
                "server.max-request-bytes", 1024, 1048576);
        int maxHttpThreads = positiveInt(envOr(p, "ARGUS_AGENT_MAX_HTTP_THREADS", "server.max-http-threads", "16"),
                "server.max-http-threads", 2, 128);
        String authToken = envOr(p, "ARGUS_AGENT_AUTH_TOKEN", "auth.token", "");
        // 真实节点即使 control.enabled=false 也会暴露容器状态，令牌缺失时必须拒绝启动。
        if (!mock && authToken.isBlank()) {
            throw new IllegalArgumentException("ARGUS_AGENT_AUTH_TOKEN/auth.token is required when executor.mock=false");
        }
        return new AgentConfig(port, id, name,
                authToken, mock,
                envOr(p, "ARGUS_AGENT_DOCKER", "docker.executable", "docker"),
                p.getProperty("instance.id", "mc01"), p.getProperty("instance.container", "mc01"),
                positiveInt(p.getProperty("executor.timeout-seconds", "30"), "executor.timeout-seconds", 1, 600),
                maxTasks, maxRequestBytes, maxHttpThreads,
                Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_CONTROL_ENABLED", "control.enabled", "false")),
                Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_DISCOVERY", "instance.discovery", "true")),
                envOr(p, "ARGUS_AGENT_ADVERTISED_HOST", "node.advertised-host", ""));
    }

    private static String envOr(Properties p, String env, String key, String fallback) {
        String value = System.getenv(env);
        return value != null && !value.isBlank() ? value : p.getProperty(key, fallback);
    }

    /** 启动时校验边界，避免错误配置在运行中才暴露。 */
    private static int positiveInt(String value, String key, int min, int max) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer between " + min + " and " + max);
        }
    }
}
