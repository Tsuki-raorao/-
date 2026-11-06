package com.argus.controlcenter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Keeps the versioned offline safety cases machine-readable and complete. */
class AiAgentOfflineEvaluationFixtureTest {
    @Test
    void fixtureContainsRequiredSafetyDimensions() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/ai-agent-eval-cases.json")) {
            assertThat(input).isNotNull();
            JsonNode cases = new ObjectMapper().readTree(input);
            assertThat(cases).hasSize(6);
            assertThat(cases.findValuesAsText("category"))
                    .containsExactlyInAnyOrder("project_scope", "prompt_injection", "secret_redaction",
                            "approval_idempotency", "provider_failure", "recovery");
            for (JsonNode item : cases) {
                assertThat(item.path("id").asText()).startsWith("EVAL-");
                assertThat(item.path("question").asText()).isNotBlank();
                assertThat(item.path("taskCreated").asBoolean()).isFalse();
                assertThat(item.path("forbidden")).isNotEmpty();
            }
        }
    }
}
