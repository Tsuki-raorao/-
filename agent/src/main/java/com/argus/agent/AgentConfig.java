package com.argus.agent;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Agent 不可变配置；配置文件可选，环境变量优先覆盖文件值。 */
public record AgentConfig(int port, String nodeId, String nodeName, String authToken,
                          boolean mock, String dockerExecutable, String defaultInstanceId,
                          String defaultContainer, int commandTimeoutSeconds,
                          int maxConcurrentTasks, int maxRequestBytes, int maxHttpThreads,
                          boolean controlEnabled, boolean discoveryEnabled, String advertisedHost,
                          int maxCommandOutputBytes, int maxHttpQueueSize,
                          String controlToken, Set<String> controlInstances, Path taskDirectory,
                          int taskMaxRecords, boolean taskDirectoryConfigured, String bindAddress,
                          boolean reviewEnabled, Set<String> reviewInstances) {
    public AgentConfig {
        bindAddress = validateBindAddress(bindAddress);
        controlInstances = Set.copyOf(controlInstances);
        reviewInstances = Set.copyOf(reviewInstances);
        if (reviewInstances.stream().anyMatch(value -> !value.matches("[A-Za-z0-9_.-]{1,64}"))) throw new IllegalArgumentException("invalid review instance allowlist");
        if (reviewEnabled && (authToken.isBlank() || controlToken.isBlank() || controlToken.equals(authToken) || reviewInstances.isEmpty()))
            throw new IllegalArgumentException("review requires distinct tokens and instance allowlist");
        if (!controlToken.isBlank() && controlToken.equals(authToken))
            throw new IllegalArgumentException("control token must differ from read token");
    }
    /** 既有构造器默认不启用人工核对。 */
    public AgentConfig(int port, String nodeId, String nodeName, String authToken, boolean mock,
                       String dockerExecutable, String defaultInstanceId, String defaultContainer, int commandTimeoutSeconds,
                       int maxConcurrentTasks, int maxRequestBytes, int maxHttpThreads, boolean controlEnabled,
                       boolean discoveryEnabled, String advertisedHost, int maxCommandOutputBytes, int maxHttpQueueSize,
                       String controlToken, Set<String> controlInstances, Path taskDirectory,
                       int taskMaxRecords, boolean taskDirectoryConfigured, String bindAddress) {
        this(port, nodeId, nodeName, authToken, mock, dockerExecutable, defaultInstanceId, defaultContainer,
                commandTimeoutSeconds, maxConcurrentTasks, maxRequestBytes, maxHttpThreads, controlEnabled, discoveryEnabled,
                advertisedHost, maxCommandOutputBytes, maxHttpQueueSize, controlToken, controlInstances, taskDirectory,
                taskMaxRecords, taskDirectoryConfigured, bindAddress, false, Set.of());
    }
    /** 保留持久任务阶段的构造方式，历史默认仍监听全部 IPv4 接口。 */
    public AgentConfig(int port, String nodeId, String nodeName, String authToken, boolean mock,
                       String dockerExecutable, String defaultInstanceId, String defaultContainer, int commandTimeoutSeconds,
                       int maxConcurrentTasks, int maxRequestBytes, int maxHttpThreads, boolean controlEnabled,
                       boolean discoveryEnabled, String advertisedHost, int maxCommandOutputBytes, int maxHttpQueueSize,
                       String controlToken, Set<String> controlInstances, Path taskDirectory,
                       int taskMaxRecords, boolean taskDirectoryConfigured) {
        this(port, nodeId, nodeName, authToken, mock, dockerExecutable, defaultInstanceId, defaultContainer,
                commandTimeoutSeconds, maxConcurrentTasks, maxRequestBytes, maxHttpThreads, controlEnabled,
                discoveryEnabled, advertisedHost, maxCommandOutputBytes, maxHttpQueueSize, controlToken, controlInstances,
                taskDirectory, taskMaxRecords, taskDirectoryConfigured, "0.0.0.0");
    }
    /** 保留既有采集测试的构造方式；只读时无意外磁盘写入。 */
    public AgentConfig(int port, String nodeId, String nodeName, String authToken, boolean mock,
                       String dockerExecutable, String defaultInstanceId, String defaultContainer, int commandTimeoutSeconds,
                       int maxConcurrentTasks, int maxRequestBytes, int maxHttpThreads, boolean controlEnabled,
                       boolean discoveryEnabled, String advertisedHost, int maxCommandOutputBytes, int maxHttpQueueSize) {
        this(port, nodeId, nodeName, authToken, mock, dockerExecutable, defaultInstanceId, defaultContainer,
                commandTimeoutSeconds, maxConcurrentTasks, maxRequestBytes, maxHttpThreads, controlEnabled,
                discoveryEnabled, advertisedHost, maxCommandOutputBytes, maxHttpQueueSize, "", Set.of(),
                Path.of("agent/data/task-inbox"), 10000, false);
    }
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
        boolean controlEnabled = Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_CONTROL_ENABLED", "control.enabled", "false"));
        String controlToken = envOr(p, "ARGUS_AGENT_CONTROL_TOKEN", "control.token", "");
        Set<String> controlInstances = Arrays.stream(envOr(p, "ARGUS_AGENT_CONTROL_INSTANCES", "control.instances", "").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (controlInstances.stream().anyMatch(value -> !value.matches("[A-Za-z0-9_.-]{1,64}"))) throw new IllegalArgumentException("invalid control instance allowlist");
        if (controlEnabled && (authToken.isBlank() || controlToken.isBlank() || controlToken.equals(authToken) || controlInstances.isEmpty()))
            throw new IllegalArgumentException("control requires distinct read/control tokens and instance allowlist");
        if (!controlToken.isBlank() && controlToken.equals(authToken)) throw new IllegalArgumentException("control token must differ from read token");
        boolean taskDirectoryConfigured = p.containsKey("task.directory") || System.getenv("ARGUS_AGENT_TASK_DIR") != null;
        boolean reviewEnabled = Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_REVIEW_ENABLED", "review.enabled", "false"));
        Set<String> reviewInstances = Arrays.stream(envOr(p, "ARGUS_AGENT_REVIEW_INSTANCES", "review.instances", "").split(","))
                .map(String::trim).filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new AgentConfig(port, id, name,
                authToken, mock,
                envOr(p, "ARGUS_AGENT_DOCKER", "docker.executable", "docker"),
                p.getProperty("instance.id", "mc01"), p.getProperty("instance.container", "mc01"),
                positiveInt(p.getProperty("executor.timeout-seconds", "30"), "executor.timeout-seconds", 1, 600),
                maxTasks, maxRequestBytes, maxHttpThreads,
                controlEnabled,
                Boolean.parseBoolean(envOr(p, "ARGUS_AGENT_DISCOVERY", "instance.discovery", "true")),
                envOr(p, "ARGUS_AGENT_ADVERTISED_HOST", "node.advertised-host", ""),
                positiveInt(envOr(p, "ARGUS_AGENT_MAX_COMMAND_OUTPUT_BYTES", "executor.max-output-bytes", "1048576"),
                        "executor.max-output-bytes", 1024, 16777216),
                positiveInt(envOr(p, "ARGUS_AGENT_MAX_HTTP_QUEUE", "server.max-http-queue", "64"),
                        "server.max-http-queue", 1, 1024), controlToken, controlInstances,
                Path.of(envOr(p, "ARGUS_AGENT_TASK_DIR", "task.directory", "agent/data/task-inbox")),
                positiveInt(envOr(p, "ARGUS_AGENT_TASK_MAX_RECORDS", "task.max-records", "10000"), "task.max-records", 1, 100000),
                taskDirectoryConfigured, bindAddress(p, System.getenv()), reviewEnabled, reviewInstances);
    }

    /** 显式空环境变量是错误配置，不能回退到文件或全接口监听。 */
    static String bindAddress(Properties properties, Map<String, String> environment) {
        String value = environment.containsKey("ARGUS_AGENT_BIND_ADDRESS")
                ? environment.get("ARGUS_AGENT_BIND_ADDRESS") : properties.getProperty("server.bind-address", "0.0.0.0");
        return validateBindAddress(value);
    }

    /** 只接受 IP 字面地址，避免主机名解析或拼写错误改变监听范围。 */
    private static String validateBindAddress(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("server.bind-address must be a non-empty IP literal");
        String address = value.trim();
        try {
            if (address.contains(":")) {
                if (address.startsWith("[") || address.endsWith("]")) throw new IllegalArgumentException();
                // 含冒号的字面量由 JDK IPv6 解析器验证，不进行 DNS 查找。
                InetAddress.getByName(address);
            } else {
                String[] octets = address.split("\\.", -1);
                if (octets.length != 4) throw new IllegalArgumentException();
                for (String octet : octets) {
                    if (!octet.matches("[0-9]{1,3}") || Integer.parseInt(octet) > 255) throw new IllegalArgumentException();
                }
                InetAddress.getByName(address);
            }
            return address;
        } catch (UnknownHostException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("server.bind-address must be a valid IPv4 or IPv6 literal");
        }
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
