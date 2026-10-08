package com.argus.agent.model;

import com.argus.agent.Json;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 七个字符串构成不可变幂等请求；任何字段变化都属于另一种请求。 */
public record TaskCommand(String commandId, String instanceId, String action, String expectedNodeId,
                          String expectedStoreId, String expectedExecutionMode, String expiresAt) {
    public static final Set<String> KEYS = Set.of("commandId", "instanceId", "action", "expectedNodeId", "expectedStoreId", "expectedExecutionMode", "expiresAt");
    public TaskCommand {
        require(uuid(commandId) && uuid(expectedStoreId));
        require(instanceId != null && instanceId.matches("[A-Za-z0-9_.-]{1,64}"));
        require(Set.of("start", "stop", "restart").contains(action == null ? "" : action));
        require(expectedNodeId != null && !expectedNodeId.isBlank() && expectedNodeId.length() <= 128);
        require(Set.of("MOCK", "DOCKER").contains(expectedExecutionMode == null ? "" : expectedExecutionMode));
        try { require(expiresAt != null && expiresAt.length() <= 64); Instant.parse(expiresAt); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid task deadline"); }
    }
    public static TaskCommand parse(String json) {
        Map<String, String> values = Json.flatObject(json);
        require(values.keySet().equals(KEYS));
        return new TaskCommand(values.get("commandId"), values.get("instanceId"), values.get("action"), values.get("expectedNodeId"),
                values.get("expectedStoreId"), values.get("expectedExecutionMode"), values.get("expiresAt"));
    }
    public String json() {
        return Json.object("commandId", commandId, "instanceId", instanceId, "action", action,
                "expectedNodeId", expectedNodeId, "expectedStoreId", expectedStoreId,
                "expectedExecutionMode", expectedExecutionMode, "expiresAt", expiresAt);
    }
    public static boolean uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return false;
        try { return UUID.fromString(value).toString().equals(value); } catch (IllegalArgumentException invalid) { return false; }
    }
    private static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("invalid task command"); }
}
