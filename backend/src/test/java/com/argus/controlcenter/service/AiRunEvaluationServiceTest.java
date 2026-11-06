package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.AiAgentRun;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class AiRunEvaluationServiceTest {
    private final AiRunEvaluationService evaluator = new AiRunEvaluationService(null, null);

    @Test
    void acceptsSanitizedWaitingApprovalRunAndReportsLatency() {
        AiAgentRun run = run("WAITING_APPROVAL");
        run.setStartedAt(Instant.parse("2026-01-01T00:00:00Z"));
        run.setFinishedAt(Instant.parse("2026-01-01T00:00:01Z"));
        run.setProviderLatencyMs(400L);

        EvaluationReport report = evaluator.evaluate(run, List.of(
                step(1, "CONTEXT", "SUCCEEDED"),
                step(2, "ANALYSIS", "SUCCEEDED"),
                step(3, "PROPOSAL", "WAITING_APPROVAL")));

        assertThat(report.passed()).isTrue();
        assertThat(report.latencyMs()).isEqualTo(1000L);
        assertThat(report.findings()).isEmpty();
    }

    @Test
    void flagsMissingApprovalAndTaskBindingBeforeExecution() {
        AiAgentRun run = run("EXECUTION_SUBMITTED");
        EvaluationReport report = evaluator.evaluate(run, List.of(
                step(1, "CONTEXT", "SUCCEEDED"),
                step(2, "ANALYSIS", "SUCCEEDED"),
                step(3, "PROPOSAL", "WAITING_APPROVAL")));

        assertThat(report.passed()).isFalse();
        assertThat(report.toolApprovalChainComplete()).isFalse();
        assertThat(report.findings()).contains("CHAIN_INCOMPLETE");
    }

    @Test
    void flagsMissingFailureCodeAndRawCredentialInPersistedEvidence() {
        AiAgentRun run = run("FAILED");
        run.setAnswer("provider response password=secret");
        EvaluationReport report = evaluator.evaluate(run, List.of(step(1, "ANALYSIS", "FAILED")));

        assertThat(report.errorCodeValid()).isFalse();
        assertThat(report.sensitiveDataSafe()).isFalse();
        assertThat(report.findings()).contains("ERROR_CODE_MISSING", "SECRET_LEAK_DETECTED");
    }

    @Test
    void flagsNegativeAndImpossibleProviderLatency() {
        AiAgentRun run = run("WAITING_APPROVAL");
        run.setStartedAt(Instant.parse("2026-01-01T00:00:02Z"));
        run.setFinishedAt(Instant.parse("2026-01-01T00:00:01Z"));
        run.setProviderLatencyMs(2_000L);
        EvaluationReport report = evaluator.evaluate(run, List.of(
                step(1, "CONTEXT", "SUCCEEDED"),
                step(2, "ANALYSIS", "SUCCEEDED"),
                step(3, "PROPOSAL", "WAITING_APPROVAL")));

        assertThat(report.latencyValid()).isFalse();
        assertThat(report.findings()).contains("LATENCY_INVALID");
    }

    @Test
    void aggregatesRecentReportsWithFixedLowCardinalityFindingCodes() {
        EvaluationReport passed = evaluator.evaluate(run("WAITING_APPROVAL"), List.of(
                step(1, "CONTEXT", "SUCCEEDED"), step(2, "ANALYSIS", "SUCCEEDED"),
                step(3, "PROPOSAL", "WAITING_APPROVAL")));
        EvaluationReport failed = evaluator.evaluate(run("FAILED"), List.of(step(1, "ANALYSIS", "FAILED")));
        EvaluationReport incomplete = evaluator.evaluate(run("EXECUTION_SUBMITTED"), List.of(
                step(1, "CONTEXT", "SUCCEEDED"), step(2, "ANALYSIS", "SUCCEEDED"),
                step(3, "PROPOSAL", "WAITING_APPROVAL")));

        AiEvaluationBatchReport report = evaluator.aggregate("project-1",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"),
                60, 100, false, List.of(passed, failed, incomplete));

        assertThat(report.evaluatedRuns()).isEqualTo(3);
        assertThat(report.passedRuns()).isEqualTo(1);
        assertThat(report.failedRuns()).isEqualTo(2);
        assertThat(report.passRate()).isEqualTo(1.0 / 3.0);
        assertThat(report.findingCounts()).containsKeys(AiRunEvaluationService.FINDING_CODES.toArray(String[]::new));
        assertThat(report.findingCounts().get("CHAIN_INCOMPLETE")).isEqualTo(1);
        assertThat(report.findingCounts().get("ERROR_CODE_MISSING")).isEqualTo(1);
    }

    private AiAgentRun run(String status) {
        AiAgentRun run = new AiAgentRun();
        run.setId("run-1");
        run.setStatus(status);
        run.setErrorCode(null);
        run.setQuestion("检查节点状态，api_key=[REDACTED]");
        return run;
    }

    private Map<String, Object> step(int sequence, String phase, String status) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("sequence_no", sequence);
        step.put("phase", phase);
        step.put("status", status);
        return step;
    }
}
