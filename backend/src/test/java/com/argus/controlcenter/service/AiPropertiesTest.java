package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AiPropertiesTest {
    @Test
    void runtimeBudgetsAreClampedToSafeBounds() {
        AiProperties properties = new AiProperties();

        properties.setMaxConcurrentRuns(0);
        properties.setMaxRunSeconds(1);
        properties.setMaxContextChars(100);
        assertThat(properties.getMaxConcurrentRuns()).isEqualTo(1);
        assertThat(properties.getMaxRunSeconds()).isEqualTo(5);
        assertThat(properties.getMaxContextChars()).isEqualTo(4000);

        properties.setMaxConcurrentRuns(99);
        properties.setMaxRunSeconds(999);
        properties.setMaxContextChars(999999);
        assertThat(properties.getMaxConcurrentRuns()).isEqualTo(16);
        assertThat(properties.getMaxRunSeconds()).isEqualTo(300);
        assertThat(properties.getMaxContextChars()).isEqualTo(200000);
    }

    @Test
    void recoveryWindowHasAnExplicitDefaultAndCanBeConfigured() {
        AiProperties properties = new AiProperties();
        assertThat(properties.getRecoveryStaleAfter()).isEqualTo(Duration.ofMinutes(2));
        properties.setRecoveryStaleAfter(Duration.ofSeconds(90));
        assertThat(properties.getRecoveryStaleAfter()).isEqualTo(Duration.ofSeconds(90));
    }
}
