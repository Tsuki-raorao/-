package com.argus.controlcenter.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiAgentRetryPolicyTest {
    @Test
    void onlyFailedAndRecoveryRunsCanBeRetried() {
        AiAgentRetryPolicy.requireRetryable("FAILED");
        AiAgentRetryPolicy.requireRetryable("RECOVERY_REQUIRED");
        assertThatThrownBy(() -> AiAgentRetryPolicy.requireRetryable("WAITING_APPROVAL"))
                .hasMessage("AI_RUN_NOT_RETRYABLE");
    }

    @Test
    void retryReasonIsRedactedAndBounded() {
        String value=AiAgentRetryPolicy.reason("Bearer abc.def password=secret "+"x".repeat(600));
        assertThat(value).doesNotContain("abc.def", "password=secret").hasSize(500);
    }
}
