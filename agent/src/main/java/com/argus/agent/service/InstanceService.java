package com.argus.agent.service;

import com.argus.agent.AgentConfig;
import com.argus.agent.command.CommandFailure;
import com.argus.agent.command.CommandRunner;
import com.argus.agent.command.ProcessCommandRunner;
import com.argus.agent.model.InstanceStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 容器采集保留来源和缺失语义；只有明确成功的发现才能返回实例清单。 */
public final class InstanceService {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern MEMORY = Pattern.compile("(?i)^([0-9]+(?:\\.[0-9]+)?)\\s*([kmgt]?i?b)?$");
    private final AgentConfig config;
    private final CommandRunner runner;

    public InstanceService(AgentConfig config) { this(config, new ProcessCommandRunner()); }
    public InstanceService(AgentConfig config, CommandRunner runner) { this.config = config; this.runner = runner; }

    public List<InstanceStatus> list() {
        if (config.mock() || !config.discoveryEnabled()) return List.of(status(config.defaultInstanceId()));
        Map<String, String> discovered = discoverContainers();
        // 成功发现为空就是空，不创建默认实例；发现失败由 HTTP 层返回脱敏的 503。
        if (discovered.isEmpty()) return List.of();
        Map<String, ContainerStats> stats = discoverStats();
        String sampledAt = Instant.now().toString();
        List<InstanceStatus> result = new ArrayList<>();
        for (var entry : discovered.entrySet()) {
            // 自动发现模式只使用真实容器名，配置别名仅属于单实例模式，不混用两个 ID。
            result.add(snapshot(entry.getKey(), entry.getKey(), entry.getValue(), stats.get(entry.getKey()), sampledAt));
        }
        return result;
    }

    public InstanceStatus status(String instanceId) {
        String container = requireKnownContainer(instanceId);
        if (config.mock()) {
            return snapshot(instanceId, container, "running", new ContainerStats(3.2, 512L * 1024 * 1024), Instant.now().toString());
        }
        String state = command("inspect", "-f", "{{.State.Status}}", container).trim();
        // stats 的键是实际容器名，不能用对外实例别名查询。
        ContainerStats stats = discoverStats().get(container);
        return snapshot(instanceId, container, state, stats, Instant.now().toString());
    }

    private Map<String, String> discoverContainers() {
        String output = command("ps", "-a", "--format", "{{.Names}}\\t{{.State}}");
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\\t", -1);
            if (parts.length != 2 || !SAFE_NAME.matcher(parts[0]).matches() || parts[1].isBlank()
                    || result.putIfAbsent(parts[0], parts[1]) != null) {
                // 非法或重复行表明清单不完整/格式异常，不悄悄丢弃后假装采集成功。
                throw new CommandFailure(CommandFailure.Reason.INVALID_OUTPUT);
            }
        }
        return result;
    }

    private Map<String, ContainerStats> discoverStats() {
        Map<String, ContainerStats> result = new LinkedHashMap<>();
        try {
            String output = command("stats", "--no-stream", "--format", "{{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}");
            for (String line : output.split("\\R")) {
                if (line.isBlank()) continue;
                String[] parts = line.split("\\t", -1);
                if (parts.length != 3 || !SAFE_NAME.matcher(parts[0]).matches()) continue;
                result.put(parts[0], new ContainerStats(parsePercent(parts[1]), parseMemory(parts[2].split("/", 2)[0].trim())));
            }
        } catch (CommandFailure unavailable) {
            // stats 失败不抹掉已成功发现的生命周期；资源字段保持 null，而不是伪造 0。
        }
        return result;
    }

    private static Double parsePercent(String value) {
        try {
            String text = value.trim();
            if (text.endsWith("%")) text = text.substring(0, text.length() - 1).trim();
            double number = Double.parseDouble(text);
            // 多核容器可以超过 100%，但负值和 NaN/Infinity 都不是有效样本。
            return Double.isFinite(number) && number >= 0 ? number : null;
        } catch (NumberFormatException invalid) { return null; }
    }

    private static Long parseMemory(String value) {
        Matcher matcher = MEMORY.matcher(value.trim());
        if (!matcher.matches()) return null;
        String unit = matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase(java.util.Locale.ROOT);
        long factor = switch (unit) {
            case "kb" -> 1000L; case "kib" -> 1024L;
            case "mb" -> 1000L * 1000; case "mib" -> 1024L * 1024;
            case "gb" -> 1000L * 1000 * 1000; case "gib" -> 1024L * 1024 * 1024;
            case "tb" -> 1000L * 1000 * 1000 * 1000; case "tib" -> 1024L * 1024 * 1024 * 1024;
            default -> 1L;
        };
        try {
            return new java.math.BigDecimal(matcher.group(1)).multiply(java.math.BigDecimal.valueOf(factor))
                    .setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException invalid) { return null; }
    }

    private InstanceStatus snapshot(String instanceId, String container, String rawState, ContainerStats stats, String sampledAt) {
        Double cpu = stats == null ? null : stats.cpuPercent();
        Long memory = stats == null ? null : stats.memoryBytes();
        String metricsStatus = cpu == null && memory == null ? "UNAVAILABLE"
                : cpu != null && memory != null ? "AVAILABLE" : "PARTIAL";
        return new InstanceStatus(instanceId, instanceId, container, lifecycle(rawState), cpu, memory,
                null, sampledAt, config.mock() ? "MOCK" : "DOCKER", sampledAt, metricsStatus);
    }

    /** 单实例 inspect 与自动发现使用同一生命周期映射，paused 不再走两套逻辑。 */
    private static String lifecycle(String rawState) {
        return switch (rawState.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "running" -> "RUNNING";
            case "created", "restarting" -> "STARTING";
            case "paused", "exited", "dead", "removing" -> "STOPPED";
            default -> "UNKNOWN";
        };
    }

    public List<String> logs(String instanceId, int limit) {
        String container = requireKnownContainer(instanceId);
        if (config.mock()) return List.of("[mock] Argus Agent connected", "[mock] instance " + instanceId + " is healthy");
        return command("logs", "--tail", String.valueOf(Math.max(1, Math.min(limit, 1000))), container).lines().toList();
    }

    public String execute(String instanceId, String action) {
        String container = requireKnownContainer(instanceId);
        if (!("start".equals(action) || "stop".equals(action) || "restart".equals(action)))
            throw new IllegalArgumentException("Unsupported action");
        if (config.mock()) return "mock " + action + " completed";
        return command(action, container).trim();
    }

    private String requireKnownContainer(String instanceId) {
        if (instanceId == null || !SAFE_NAME.matcher(instanceId).matches()) throw new IllegalArgumentException("Invalid instance id");
        if (config.mock() || !config.discoveryEnabled()) {
            if (instanceId.equals(config.defaultInstanceId()) && SAFE_NAME.matcher(config.defaultContainer()).matches())
                return config.defaultContainer();
        } else if (discoverContainers().containsKey(instanceId)) {
            return instanceId;
        }
        throw new IllegalArgumentException("Unknown instance id");
    }

    private String command(String... args) {
        List<String> command = new ArrayList<>();
        command.add(config.dockerExecutable());
        java.util.Collections.addAll(command, args);
        return runner.run(List.copyOf(command), config.commandTimeoutSeconds(), config.maxCommandOutputBytes());
    }

    private record ContainerStats(Double cpuPercent, Long memoryBytes) { }
}
