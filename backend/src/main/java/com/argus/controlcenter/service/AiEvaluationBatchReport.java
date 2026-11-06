package com.argus.controlcenter.service;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only aggregate of persisted run evaluations.  It deliberately contains
 * counts and fixed finding codes only; run ids and run content are not exposed.
 */
public record AiEvaluationBatchReport(
        String projectId,
        Instant windowStart,
        Instant windowEnd,
        int windowMinutes,
        int limit,
        int evaluatedRuns,
        int passedRuns,
        int failedRuns,
        double passRate,
        boolean truncated,
        Map<String, Integer> findingCounts) {

    public AiEvaluationBatchReport {
        findingCounts = findingCounts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(findingCounts));
    }
}
