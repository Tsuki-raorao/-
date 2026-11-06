package com.argus.controlcenter.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiAgentMetricsTest {
    @Test
    void recordsLowCardinalityRunAndProviderMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiAgentMetrics metrics = new AiAgentMetrics(registry);

        metrics.recordRun("proposal", "PROPOSAL", Duration.ofMillis(12));
        metrics.recordProvider(Duration.ofMillis(8), "success");
        metrics.recordConcurrencyRejected();

        assertThat(registry.get("argus.ai.runs").counter().count()).isEqualTo(1);
        assertThat(registry.get("argus.ai.run.duration").timer().count()).isEqualTo(1);
        assertThat(registry.get("argus.ai.provider.requests").counter().count()).isEqualTo(1);
        assertThat(registry.get("argus.ai.concurrency.rejected").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allMatch(m -> m.getId().getTags().stream().noneMatch(t ->
                t.getKey().equals("project") || t.getKey().equals("actor") || t.getKey().equals("runId")));
    }
}
