package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AiRetentionProperties;
import com.argus.controlcenter.repository.AiAgentRetentionRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Scheduled retention; active approval and recovery runs are never purged. */
@Service
public class AiAgentRetentionService {
    private final AiAgentRetentionRepository repository;
    private final AiRetentionProperties properties;

    public AiAgentRetentionService(AiAgentRetentionRepository repository, AiRetentionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${ARGUS_AI_RETENTION_INITIAL_DELAY_MS:3600000}",
            fixedDelayString = "${ARGUS_AI_RETENTION_INTERVAL_MS:86400000}")
    public void purgeExpiredEvidence() {
        if (!properties.isEnabled()) return;
        repository.purge(Instant.now().minus(properties.getRetentionDays(), ChronoUnit.DAYS), properties.getBatchSize());
    }
}
