package com.argus.agent.model;

import com.argus.agent.Json;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 固定核对授权不触发动作；原命令期限只作身份比对，不为核对增加 TTL。 */
public record ResolutionRequest(String commandId, String resolutionId, String instanceId, String action,
        String expectedNodeId, String expectedStoreId, String expectedExecutionMode, String expectedCommandExpiresAt,
        String decision, String reason, String evidenceDigest, boolean acknowledgeNoReplay, boolean acknowledgeResidualRisk) {
    private static final Set<String> ACKS = Set.of("acknowledgeNoReplay", "acknowledgeResidualRisk");
    private static final Set<String> KEYS = Set.of("resolutionId", "instanceId", "action", "expectedNodeId", "expectedStoreId",
            "expectedExecutionMode", "expectedCommandExpiresAt", "decision", "reason", "evidenceDigest", "acknowledgeNoReplay", "acknowledgeResidualRisk");
    public ResolutionRequest {
        require(TaskCommand.uuid(commandId) && TaskCommand.uuid(resolutionId) && TaskCommand.uuid(expectedStoreId));
        require(instanceId != null && instanceId.matches("[A-Za-z0-9_.-]{1,64}"));
        require(Set.of("start", "stop", "restart").contains(action == null ? "" : action));
        require(expectedNodeId != null && !expectedNodeId.isBlank() && expectedNodeId.length() <= 128);
        require(Set.of("MOCK", "DOCKER").contains(expectedExecutionMode == null ? "" : expectedExecutionMode));
        require(expectedCommandExpiresAt != null && expectedCommandExpiresAt.length() <= 64);
        try { Instant.parse(expectedCommandExpiresAt); } catch (RuntimeException invalid) { throw new IllegalArgumentException("invalid resolution request"); }
        require("ACKNOWLEDGE_UNCERTAINTY".equals(decision) && acknowledgeNoReplay && acknowledgeResidualRisk);
        // 中央已持久化标准文本；这里只验证，不能二次裁剪后改变请求 hash。
        require(reason != null && !reason.isEmpty() && !ecmaWhitespace(reason.charAt(0))
                && !ecmaWhitespace(reason.charAt(reason.length() - 1)) && reason.indexOf('\r') < 0
                && reason.codePointCount(0, reason.length()) <= 500);
        require(evidenceDigest != null && evidenceDigest.matches("[0-9a-f]{64}"));
        for (String value : List.of(expectedNodeId, reason)) require(validText(value));
    }
    private static boolean ecmaWhitespace(char value) {
        return value >= '\u0009' && value <= '\r' || value == ' ' || value == '\u00a0' || value == '\u1680'
                || value >= '\u2000' && value <= '\u200a' || value == '\u2028' || value == '\u2029'
                || value == '\u202f' || value == '\u205f' || value == '\u3000' || value == '\ufeff';
    }
    private static boolean validText(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) return false;
            if (Character.isHighSurrogate(c) && (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))) return false;
        }
        return true;
    }
    public static ResolutionRequest parse(String commandId, String body) {
        Map<String, String> v = Json.flatObjectWithBooleans(body, ACKS);
        require(v.keySet().equals(KEYS));
        return new ResolutionRequest(commandId, v.get("resolutionId"), v.get("instanceId"), v.get("action"), v.get("expectedNodeId"),
                v.get("expectedStoreId"), v.get("expectedExecutionMode"), v.get("expectedCommandExpiresAt"), v.get("decision"),
                v.get("reason"), v.get("evidenceDigest"), "true".equals(v.get("acknowledgeNoReplay")), "true".equals(v.get("acknowledgeResidualRisk")));
    }
    public boolean matches(TaskCommand command) {
        return commandId.equals(command.commandId()) && instanceId.equals(command.instanceId()) && action.equals(command.action())
                && expectedNodeId.equals(command.expectedNodeId()) && expectedStoreId.equals(command.expectedStoreId())
                && expectedExecutionMode.equals(command.expectedExecutionMode()) && expectedCommandExpiresAt.equals(command.expiresAt());
    }
    public String json() { return Json.object("resolutionId", resolutionId, "instanceId", instanceId, "action", action,
            "expectedNodeId", expectedNodeId, "expectedStoreId", expectedStoreId, "expectedExecutionMode", expectedExecutionMode,
            "expectedCommandExpiresAt", expectedCommandExpiresAt, "decision", decision, "reason", reason, "evidenceDigest", evidenceDigest,
            "acknowledgeNoReplay", acknowledgeNoReplay, "acknowledgeResidualRisk", acknowledgeResidualRisk); }
    public String requestHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : List.of("argus-resolution-v1", commandId, resolutionId, instanceId, action, expectedNodeId, expectedStoreId,
                    expectedExecutionMode, expectedCommandExpiresAt, decision, reason, evidenceDigest, "true", "true")) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("hash unavailable"); }
    }
    private static void require(boolean value) { if (!value) throw new IllegalArgumentException("invalid resolution request"); }
}
