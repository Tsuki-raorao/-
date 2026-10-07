package com.argus.agent.service;

import com.argus.agent.AgentConfig;
import com.argus.agent.model.InstanceStatus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

public final class InstanceService {
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private final AgentConfig config;
    public InstanceService(AgentConfig config) { this.config = config; }

    /**
     * 读取节点上的容器清单。真实模式优先使用 docker ps -a 自动发现，
     * 这样同一台机器上的多个 Minecraft 或 Web 服务不会被硬编码漏掉。
     * 发现失败时保留默认实例的 UNKNOWN 状态，避免把权限问题伪装成空列表。
     */
    public List<InstanceStatus> list() {
        if (config.mock() || !config.discoveryEnabled()) return List.of(status(config.defaultInstanceId()));
        try {
            Map<String, String> discovered = discoverContainers();
            if (discovered.isEmpty()) return List.of(status(config.defaultInstanceId()));
            // docker stats 是只读快照；命令失败时仍返回容器生命周期状态，不伪造异常。
            Map<String, ContainerStats> stats = discoverStats();
            List<InstanceStatus> result = new ArrayList<>();
            for (Map.Entry<String, String> entry : discovered.entrySet()) {
                result.add(statusFromState(entry.getKey(), entry.getValue(), stats.get(entry.getKey())));
            }
            return result;
        } catch (Exception e) {
            return List.of(status(config.defaultInstanceId()));
        }
    }

    public InstanceStatus status(String instanceId) {
        requireKnownInstance(instanceId);
        String state = "RUNNING";
        long memory = config.mock() ? 512L * 1024 * 1024 : 0;
        if (!config.mock()) {
            try {
                CommandResult r = command("inspect", "-f", "{{.State.Status}}", container(instanceId));
                state = switch (r.output().trim().toLowerCase()) {
                    case "running" -> "RUNNING"; case "created", "restarting" -> "STARTING";
                    case "paused", "exited", "dead", "removing" -> "STOPPED"; default -> "UNKNOWN";
                };
            } catch (Exception e) { state = "UNKNOWN"; }
        }
        ContainerStats stats = config.mock() ? null : discoverStats().get(instanceId);
        double cpu = config.mock() ? 3.2 : stats == null ? 0 : stats.cpuPercent();
        if (!config.mock() && stats != null) memory = stats.memoryBytes();
        return new InstanceStatus(instanceId, instanceId, container(instanceId), state, cpu,
                memory, 0, Instant.now().toString());
    }

    private Map<String, String> discoverContainers() throws Exception {
        CommandResult r = command("ps", "-a", "--format", "{{.Names}}\\t{{.State}}");
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : r.output().split("\\R")) {
            String[] parts = line.split("\\t", 2);
            if (parts.length != 2 || !SAFE_NAME.matcher(parts[0]).matches()) continue;
            result.put(parts[0], parts[1]);
        }
        return result;
    }

    private Map<String, ContainerStats> discoverStats() {
        Map<String, ContainerStats> result = new LinkedHashMap<>();
        try {
            CommandResult r = command("stats", "--no-stream", "--format", "{{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}");
            for (String line : r.output().split("\\R")) {
                String[] parts = line.split("\\t", 3);
                if (parts.length != 3 || !SAFE_NAME.matcher(parts[0]).matches()) continue;
                result.put(parts[0], new ContainerStats(parsePercent(parts[1]), parseMemory(parts[2].split("/", 2)[0].trim())));
            }
        } catch (Exception ignored) {
            // 部分节点可能只允许 docker ps；状态仍然可用，资源值保持 0。
        }
        return result;
    }

    private double parsePercent(String value) {
        try { return Double.parseDouble(value.replace("%", "").trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    /** 把 Docker 的 KiB/MiB/GiB 等文本转成字节，无法解析时返回 0。 */
    private long parseMemory(String value) {
        Matcher matcher = Pattern.compile("(?i)^([0-9]+(?:\\.[0-9]+)?)\\s*([kmgt]?i?b)?$").matcher(value.trim());
        if (!matcher.matches()) return 0;
        try {
            double number = Double.parseDouble(matcher.group(1));
            String unit = matcher.group(2) == null ? "b" : matcher.group(2).toLowerCase();
            double factor = switch (unit) {
                case "kb" -> 1000d; case "kib" -> 1024d;
                case "mb" -> 1000d * 1000; case "mib" -> 1024d * 1024;
                case "gb" -> 1000d * 1000 * 1000; case "gib" -> 1024d * 1024 * 1024;
                case "tb" -> 1000d * 1000 * 1000 * 1000; case "tib" -> 1024d * 1024 * 1024 * 1024d;
                default -> 1d;
            };
            return Math.max(0, Math.round(number * factor));
        } catch (NumberFormatException e) { return 0; }
    }

    private InstanceStatus statusFromState(String instanceId, String rawState, ContainerStats stats) {
        String value = rawState == null ? "" : rawState.toLowerCase();
        String state = value.startsWith("running") ? "RUNNING"
                : value.startsWith("created") || value.startsWith("restarting") ? "STARTING"
                : value.startsWith("exited") || value.startsWith("dead") || value.startsWith("removing") ? "STOPPED"
                : "UNKNOWN";
        return new InstanceStatus(instanceId, instanceId, container(instanceId), state,
                stats == null ? 0 : stats.cpuPercent(), stats == null ? 0 : stats.memoryBytes(), 0,
                Instant.now().toString());
    }

    public List<String> logs(String instanceId, int limit) throws Exception {
        requireKnownInstance(instanceId); limit = Math.max(1, Math.min(limit, 1000));
        if (config.mock()) return List.of("[mock] Argus Agent connected", "[mock] instance " + instanceId + " is healthy");
        return lines(command("logs", "--tail", String.valueOf(limit), container(instanceId)).output());
    }

    public String execute(String instanceId, String action) throws Exception {
        requireKnownInstance(instanceId);
        if (!(action.equals("start") || action.equals("stop") || action.equals("restart")))
            throw new IllegalArgumentException("Unsupported action: " + action);
        if (config.mock()) return "mock " + action + " completed";
        return command(action, container(instanceId)).output().trim();
    }

    private String container(String instanceId) { return instanceId.equals(config.defaultInstanceId()) ? config.defaultContainer() : instanceId; }
    private void requireSafe(String value) { if (value == null || !SAFE_NAME.matcher(value).matches()) throw new IllegalArgumentException("Invalid instance id"); }
    /**
     * 只允许默认实例或 docker ps -a 已发现的实例，拒绝把任意安全字符串
     * 当作 Docker 容器名读取，避免日志查询和未来控制接口越权访问。
     */
    private void requireKnownInstance(String instanceId) {
        requireSafe(instanceId);
        if (instanceId.equals(config.defaultInstanceId())) return;
        if (!config.mock() && config.discoveryEnabled()) {
            try {
                if (discoverContainers().containsKey(instanceId)) return;
            } catch (Exception ignored) {
                // 发现失败时保持拒绝，不能因为 Docker 权限异常放宽边界。
            }
        }
        throw new IllegalArgumentException("Unknown instance id");
    }
    private CommandResult command(String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add(config.dockerExecutable()); java.util.Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(config.commandTimeoutSeconds(), TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Docker command timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("Docker command failed: " + output);
        return new CommandResult(process.exitValue(), output);
    }
    private List<String> lines(String text) throws Exception { try (BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8))))) { return r.lines().toList(); } }
    private record CommandResult(int exitCode, String output) { }
    private record ContainerStats(double cpuPercent, long memoryBytes) { }
}
