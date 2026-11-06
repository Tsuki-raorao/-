package com.argus.controlcenter.service;

import java.util.Set;

/** Central policy for explicit, human initiated Agent retries. */
public final class AiAgentRetryPolicy {
    private static final Set<String> RETRYABLE = Set.of("FAILED", "RECOVERY_REQUIRED");

    private AiAgentRetryPolicy() { }

    public static void requireRetryable(String status) {
        if (!RETRYABLE.contains(status)) throw new IllegalStateException("AI_RUN_NOT_RETRYABLE");
    }

    public static String reason(String value) {
        String safe = AiAgentInputSanitizer.redact(value == null ? "" : value).trim();
        if (safe.isBlank()) return "manual retry";
        return safe.length() > 500 ? safe.substring(0, 500) : safe;
    }
}
