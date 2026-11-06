package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.AiAgentRun;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.repository.AiAgentRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Evaluates facts already persisted by the Agent.  It has no provider client,
 * retry behavior or knowledge-base dependency, so evaluation cannot execute
 * a model or mutate an AI run.
 */
@Service
public class AiRunEvaluationService {
    private static final int MAX_WINDOW_MINUTES = 7 * 24 * 60;
    private static final int MAX_BATCH_SIZE = 500;
    /** Fixed, low-cardinality finding vocabulary used by reports and gates. */
    static final List<String> FINDING_CODES = List.of(
            "STATUS_UNKNOWN", "ERROR_CODE_MISSING", "LATENCY_INVALID",
            "CHAIN_INCOMPLETE", "SECRET_LEAK_DETECTED");
    private static final Set<String> KNOWN_STATUSES = Set.of(
            "RUNNING", "WAITING_APPROVAL", "EXECUTION_SUBMITTED", "COMPLETED",
            "EXECUTION_FAILED", "VERIFICATION_PENDING", "FAILED", "RECOVERY_REQUIRED");
    private static final Set<String> FAILURE_STATUSES = Set.of("FAILED", "EXECUTION_FAILED", "RECOVERY_REQUIRED");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\s+(?!\\[REDACTED\\])[^\\s,;]+", Pattern.MULTILINE);
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|password|secret|token)\\s*[:=]\\s*(?!\\[REDACTED(?:_[A-Z]+)?\\])[^\\s,;]+",
            Pattern.MULTILINE);
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{8,}\\b");

    private final AiAgentRepository repository;
    private final ProjectAuthorization authorization;

    public AiRunEvaluationService(AiAgentRepository repository, ProjectAuthorization authorization) {
        this.repository = repository;
        this.authorization = authorization;
    }

    /** Evaluate one persisted run after normal project/actor authorization. */
    public EvaluationReport evaluate(String id, String projectId, CurrentActor actor) {
        String project = scope(projectId);
        authorization.require(actor, project, ProjectPermission.RESOURCE_READ);
        AiAgentRun run = repository.getRun(id, project, actor.userId())
                .orElseThrow(() -> new IllegalArgumentException("AI_RUN_NOT_FOUND"));
        return evaluate(run, repository.steps(id));
    }

    /** Pure evaluation entry point used by offline and service tests. */
    public EvaluationReport evaluate(AiAgentRun run, List<Map<String, Object>> steps) {
        if (run == null) throw new IllegalArgumentException("AI_RUN_REQUIRED");
        List<String> findings = new ArrayList<>();
        String status = run.getStatus();
        boolean statusValid = status != null && KNOWN_STATUSES.contains(status);
        if (!statusValid) findings.add("STATUS_UNKNOWN");

        boolean errorCodeValid = !FAILURE_STATUSES.contains(status) || nonBlank(run.getErrorCode());
        if (!errorCodeValid) findings.add("ERROR_CODE_MISSING");

        Long latencyMs = elapsedMillis(run.getStartedAt(), run.getFinishedAt());
        boolean latencyValid = latencyMs != null || run.getFinishedAt() == null;
        if (latencyMs != null && latencyMs < 0) latencyValid = false;
        if (run.getProviderLatencyMs() != null && run.getProviderLatencyMs() < 0) latencyValid = false;
        if (latencyMs != null && run.getProviderLatencyMs() != null && run.getProviderLatencyMs() > latencyMs) latencyValid = false;
        if (!latencyValid) findings.add("LATENCY_INVALID");

        boolean chainComplete = chainComplete(run, steps == null ? List.of() : steps);
        if (!chainComplete) findings.add("CHAIN_INCOMPLETE");

        boolean sensitiveDataSafe = sensitiveDataSafe(run, steps == null ? List.of() : steps);
        if (!sensitiveDataSafe) findings.add("SECRET_LEAK_DETECTED");

        boolean passed = statusValid && errorCodeValid && latencyValid && chainComplete && sensitiveDataSafe;
        return new EvaluationReport(run.getId(), status, run.getErrorCode(), latencyMs,
                run.getProviderLatencyMs(), statusValid, errorCodeValid, latencyValid,
                chainComplete, sensitiveDataSafe, passed, findings);
    }

    /**
     * Evaluate recent runs in one project without invoking a provider or
     * changing any run/task state.  Only aggregate counts are returned.
     */
    public AiEvaluationBatchReport evaluateBatch(String projectId, int windowMinutes, int limit,
                                                  CurrentActor actor) {
        String project = scope(projectId);
        authorization.require(actor, project, ProjectPermission.RESOURCE_READ);
        int minutes = bounded(windowMinutes, 1, MAX_WINDOW_MINUTES);
        int requested = bounded(limit, 1, MAX_BATCH_SIZE);
        Instant end = Instant.now();
        Instant start = end.minus(Duration.ofMinutes(minutes));
        List<AiAgentRun> runs = repository.findRuns(project, start, requested + 1);
        boolean truncated = runs.size() > requested;
        List<AiAgentRun> selected = truncated ? runs.subList(0, requested) : runs;
        List<EvaluationReport> reports = new ArrayList<>(selected.size());
        for (AiAgentRun run : selected) reports.add(evaluate(run, repository.steps(run.getId())));
        return aggregate(project, start, end, minutes, requested, truncated, reports);
    }

    /** Pure aggregate operation used by service and offline tests. */
    AiEvaluationBatchReport aggregate(String projectId, Instant start, Instant end,
                                      int windowMinutes, int limit, boolean truncated,
                                      List<EvaluationReport> reports) {
        List<EvaluationReport> values = reports == null ? List.of() : reports;
        int passed = (int) values.stream().filter(EvaluationReport::passed).count();
        Map<String, Integer> findings = new LinkedHashMap<>();
        for (String code : FINDING_CODES) findings.put(code, 0);
        for (EvaluationReport report : values) {
            for (String finding : report.findings()) {
                // Ignore unknown values so the public vocabulary stays bounded.
                if (findings.containsKey(finding)) findings.computeIfPresent(finding, (k, v) -> v + 1);
            }
        }
        int total = values.size();
        // An empty window has no evidence for a release gate, so it reports a
        // zero rate rather than looking like a successful 100% sample.
        double passRate = total == 0 ? 0.0 : (double) passed / total;
        return new AiEvaluationBatchReport(projectId, start, end, windowMinutes, limit,
                total, passed, total - passed, passRate, truncated, findings);
    }

    private boolean chainComplete(AiAgentRun run, List<Map<String, Object>> steps) {
        if (steps.isEmpty()) return false;
        Set<String> phases = new HashSet<>();
        boolean contextOk = false, analysisOk = false, proposalOk = false;
        boolean approvalOk = false, verificationSeen = false, recoveryOk = false, failedSeen = false;
        int expectedSequence = 1;
        for (Map<String, Object> step : steps) {
            String phase = text(step.get("phase"));
            String stepStatus = text(step.get("status"));
            Number sequence = number(step.get("sequence_no"));
            if (sequence != null && sequence.intValue() != expectedSequence++) return false;
            phases.add(phase);
            if ("CONTEXT".equals(phase) && "SUCCEEDED".equals(stepStatus)) contextOk = true;
            if ("ANALYSIS".equals(phase) && "SUCCEEDED".equals(stepStatus)) analysisOk = true;
            if ("PROPOSAL".equals(phase) && "WAITING_APPROVAL".equals(stepStatus)) proposalOk = true;
            if ("APPROVAL".equals(phase) && "APPROVED".equals(stepStatus)) approvalOk = true;
            if ("VERIFICATION".equals(phase)) verificationSeen = true;
            if ("RECOVERY".equals(phase) && "RECOVERY_REQUIRED".equals(stepStatus)) recoveryOk = true;
            if ("FAILED".equals(stepStatus)) failedSeen = true;
        }
        if (!KNOWN_STATUSES.contains(run.getStatus())) return false;
        return switch (run.getStatus()) {
            case "WAITING_APPROVAL" -> contextOk && analysisOk && proposalOk;
            case "EXECUTION_SUBMITTED" -> contextOk && analysisOk && proposalOk && approvalOk && nonBlank(run.getTaskId());
            case "COMPLETED", "EXECUTION_FAILED", "VERIFICATION_PENDING" ->
                    contextOk && analysisOk && proposalOk && approvalOk && verificationSeen && nonBlank(run.getTaskId());
            case "FAILED" -> failedSeen;
            case "RECOVERY_REQUIRED" -> recoveryOk;
            // A live run has not completed its chain yet and must not pass a
            // release gate until it reaches an auditable state.
            case "RUNNING" -> false;
            default -> false;
        };
    }

    private boolean sensitiveDataSafe(AiAgentRun run, List<Map<String, Object>> steps) {
        return Arrays.stream(new String[]{run.getQuestion(), run.getAnswer(), run.getEvidenceJson(),
                        run.getActionPlanJson(), run.getVerificationJson(), run.getErrorCode()})
                .filter(Objects::nonNull).noneMatch(this::containsSecret)
                && steps.stream().noneMatch(this::containsSecret);
    }

    private boolean containsSecret(Object value) {
        if (value == null) return false;
        if (value instanceof Map<?, ?> map) return map.entrySet().stream().anyMatch(e -> containsSecret(e.getKey()) || containsSecret(e.getValue()));
        if (value instanceof Iterable<?> values) {
            for (Object item : values) if (containsSecret(item)) return true;
            return false;
        }
        String text = String.valueOf(value);
        return BEARER.matcher(text).find() || ASSIGNMENT.matcher(text).find() || SECRET_KEY.matcher(text).find();
    }

    private Long elapsedMillis(Instant started, Instant finished) {
        if (started == null || finished == null) return null;
        try { return Duration.between(started, finished).toMillis(); }
        catch (RuntimeException ignored) { return null; }
    }

    private String scope(String project) {
        if (project == null || project.isBlank()) {
            if (authorization.isIdentityMode()) throw new IdentityAuthorizationException("PROJECT_REQUIRED", 400);
            return IdentityConstants.LEGACY_PROJECT_ID;
        }
        return project;
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }
    private static int bounded(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Number number(Object value) { return value instanceof Number n ? n : null; }
}
