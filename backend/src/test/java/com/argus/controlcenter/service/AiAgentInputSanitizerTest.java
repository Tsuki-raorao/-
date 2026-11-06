package com.argus.controlcenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentInputSanitizerTest {
    @Test
    void removesCredentialsBeforePersistenceOrProviderPrompt() {
        String safe = AiAgentInputSanitizer.question(
                "检查节点，Authorization: Bearer abc.def, api_key=sk-1234567890abcdef password:secret");

        assertThat(safe).doesNotContain("abc.def", "sk-1234567890abcdef", "password:secret");
        assertThat(safe).contains("Bearer [REDACTED]", "api_key=[REDACTED]");
    }

    @Test
    void rejectsBlankQuestionsAndBoundsLargeInput() {
        assertThatThrownBy(() -> AiAgentInputSanitizer.question("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AI_QUESTION_REQUIRED");
        assertThat(AiAgentInputSanitizer.question("x".repeat(13000)))
                .hasSize(12000 + "\n[QUESTION_TRUNCATED]".length())
                .endsWith("[QUESTION_TRUNCATED]");
    }
}
