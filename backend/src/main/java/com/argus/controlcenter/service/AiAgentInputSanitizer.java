package com.argus.controlcenter.service;

/** Normalizes user text before it is persisted or sent to an AI provider. */
public final class AiAgentInputSanitizer {
    private AiAgentInputSanitizer() { }

    public static String question(String value) {
        String safe = redact(value == null ? "" : value).trim();
        if (safe.isBlank()) throw new IllegalArgumentException("AI_QUESTION_REQUIRED");
        return safe.length() > 12000 ? safe.substring(0, 12000) + "\n[QUESTION_TRUNCATED]" : safe;
    }

    public static String redact(String value) {
        if (value == null || value.isEmpty()) return "";
        return value
                .replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*[^\\s,;]+", "$1=[REDACTED]")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b", "[REDACTED_KEY]");
    }
}
