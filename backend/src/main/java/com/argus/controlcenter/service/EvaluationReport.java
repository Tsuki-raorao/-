package com.argus.controlcenter.service;

import java.util.List;

/**
 * Read-only safety report for a persisted AI run.  The report deliberately
 * contains metric names and finding codes only; it never copies run content
 * or a detected secret into an API response.
 */
public record EvaluationReport(
        String runId,
        String status,
        String errorCode,
        Long latencyMs,
        Long providerLatencyMs,
        boolean statusValid,
        boolean errorCodeValid,
        boolean latencyValid,
        boolean toolApprovalChainComplete,
        boolean sensitiveDataSafe,
        boolean passed,
        List<String> findings) {

    public EvaluationReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
