package com.argus.controlcenter.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Low-cardinality Agent metrics. Project, actor, question, answer and run IDs
 * are deliberately excluded so metrics remain safe to expose to Prometheus.
 */
@Component
public class AiAgentMetrics {
    private final MeterRegistry registry;

    public AiAgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRun(String outcome, String phase, Duration duration) {
        Counter.builder("argus.ai.runs")
                .description("AI Agent runs by terminal outcome")
                .tag("outcome", safe(outcome))
                .tag("phase", safe(phase))
                .register(registry)
                .increment();
        Timer.builder("argus.ai.run.duration")
                .description("AI Agent run duration")
                .tag("outcome", safe(outcome))
                .register(registry)
                .record(duration);
    }

    public void recordProvider(Duration duration, String result) {
        Timer.builder("argus.ai.provider.duration")
                .description("AI provider request duration")
                .tag("result", safe(result))
                .register(registry)
                .record(duration);
        Counter.builder("argus.ai.provider.requests")
                .description("AI provider requests")
                .tag("result", safe(result))
                .register(registry)
                .increment();
    }

    public void recordConcurrencyRejected() {
        Counter.builder("argus.ai.concurrency.rejected")
                .description("AI Agent runs rejected by concurrency budget")
                .register(registry)
                .increment();
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.length() > 32 ? value.substring(0, 32) : value;
    }
}
