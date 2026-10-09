package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.TaskResolution;
import com.argus.controlcenter.exception.NotFoundException;
import com.argus.controlcenter.identity.*;
import com.argus.controlcenter.repository.TaskResolutionRepository;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** BLOCKED 核对记录的显式重新授权；只恢复核对流程，不重放原控制命令。 */
@Service
public class TaskReviewAuthorizationService {
    private final JdbcTemplate jdbc;
    private final TaskResolutionRepository resolutions;
    private final ProjectAuthorization authorization;
    private final TransactionTemplate tx;

    public TaskReviewAuthorizationService(JdbcTemplate jdbc, TaskResolutionRepository resolutions,
                                          ProjectAuthorization authorization, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.resolutions = resolutions; this.authorization = authorization; this.tx = new TransactionTemplate(manager);
    }

    public Grant adopt(String resolutionId, String requestKey, String projectId, AdoptionRequest body, CurrentActor actor) {
        if (!authorization.isIdentityMode()) throw new IdentityAuthorizationException("IDENTITY_REQUIRED", 403);
        validateKey(requestKey);
        validateBody(body);
        String expectedHash = body.expectedRequestHash().toLowerCase(Locale.ROOT);
        return tx.execute(ignored -> {
            lockGate();
            authorization.requireForUpdate(actor.userId(), actor.authMode(), projectId, ProjectPermission.TASK_REVIEW);
            var row = resolutions.lock(resolutionId);
            if (!projectId.equals(row.original().task().getProjectId())) throw notFound();
            Grant existing = findByActorKey(resolutionId, projectId, actor.userId(), requestKey);
            if (existing != null) {
                if (!MessageDigest.isEqual(existing.requestHash().getBytes(java.nio.charset.StandardCharsets.UTF_8), expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    throw new IdentityAuthorizationException("REQUEST_IDEMPOTENCY_CONFLICT", 409);
                return existing;
            }
            if (!"BLOCKED".equals(row.status())) throw new IdentityAuthorizationException("RESOLUTION_NOT_ADOPTABLE", 409);
            if (!MessageDigest.isEqual(row.requestHash().getBytes(java.nio.charset.StandardCharsets.UTF_8), expectedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                throw new IdentityAuthorizationException("REQUEST_HASH_MISMATCH", 409);
            if (row.activeAuthorizationId() != null) throw new IdentityAuthorizationException("RESOLUTION_ALREADY_AUTHORIZED", 409);
            String grantId = UUID.randomUUID().toString();
            Instant now = Instant.now();
            jdbc.update("INSERT INTO resolution_authorizations(id,resolution_id,project_id,actor_user_id,auth_mode,request_key,request_hash,reason,acknowledge_no_replay,acknowledge_residual_risk,kind,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    grantId, resolutionId, projectId, actor.userId(), actor.authMode().name(), requestKey, expectedHash, body.reason(), true, true, "ADOPTION", Timestamp.from(now));
            resolutions.activateAuthorization(resolutionId, grantId);
            if (!resolutions.transition(row, "PENDING", "REVIEW_REAUTHORIZED", actor.userId(), row.attempts(), 0, null, now))
                throw new IdentityAuthorizationException("VERSION_CONFLICT", 409);
            jdbc.update("INSERT INTO task_resolution_queue(resolution_id,next_run_at) VALUES(?,?)", resolutionId, Timestamp.from(now));
            return new Grant(grantId, resolutionId, projectId, actor.userId(), actor.authMode().name(), requestKey, expectedHash, body.reason(), true, true, "ADOPTION", now);
        });
    }

    public Grant find(String resolutionId, String requestKey, String projectId, CurrentActor actor) {
        if (!authorization.isIdentityMode()) throw new IdentityAuthorizationException("IDENTITY_REQUIRED", 403);
        validateKey(requestKey);
        authorization.require(actor, projectId, ProjectPermission.TASK_REVIEW);
        Grant result = findByActorKey(resolutionId, projectId, actor.userId(), requestKey);
        if (result == null) throw notFound();
        return result;
    }

    private Grant findByActorKey(String resolutionId, String projectId, String actorUserId, String requestKey) {
        List<Grant> rows = jdbc.query("SELECT id,resolution_id,project_id,actor_user_id,auth_mode,request_key,request_hash,reason,acknowledge_no_replay,acknowledge_residual_risk,kind,created_at FROM resolution_authorizations WHERE resolution_id=? AND project_id=? AND actor_user_id=? AND request_key=?",
                (rs, n) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getBoolean(9), rs.getBoolean(10), rs.getString(11), rs.getTimestamp(12).toInstant()),
                resolutionId, projectId, actorUserId, requestKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void lockGate() { jdbc.queryForObject("SELECT id FROM identity_config_gate WHERE id=? FOR UPDATE", String.class, IdentityConstants.IDENTITY_CONFIG_GATE_ID); }
    private static void validateKey(String key) { try { if (key == null || !UUID.fromString(key).toString().equals(key)) throw new IllegalArgumentException(); } catch (IllegalArgumentException e) { throw new IdentityAuthorizationException("INVALID_IDEMPOTENCY_KEY", 400); } }
    private static void validateBody(AdoptionRequest body) {
        if (body == null || body.expectedRequestHash() == null || !body.expectedRequestHash().matches("[0-9a-fA-F]{64}") || body.reason() == null || body.reason().isBlank() || body.reason().length() > 500 || !body.acknowledgeNoReplay() || !body.acknowledgeResidualRisk())
            throw new IdentityAuthorizationException("INVALID_REQUEST", 400);
    }
    private static IdentityAuthorizationException notFound() { return new IdentityAuthorizationException("REQUEST_NOT_FOUND", 404); }

    public record AdoptionRequest(String expectedRequestHash, String reason, boolean acknowledgeNoReplay, boolean acknowledgeResidualRisk) { }
    public record Grant(String id, String resolutionId, String projectId, String actorUserId, String authMode,
                        String requestKey, String requestHash, String reason, boolean acknowledgeNoReplay,
                        boolean acknowledgeResidualRisk, String kind, Instant createdAt) { }
}
