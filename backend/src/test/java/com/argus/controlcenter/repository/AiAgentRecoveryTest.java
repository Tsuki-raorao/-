package com.argus.controlcenter.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies that an interrupted persisted run becomes explicitly recoverable. */
@SpringBootTest
@ActiveProfiles("test")
class AiAgentRecoveryTest {
    @Autowired private AiAgentRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void staleRunningRunIsRecoveredExactlyOnce() {
        String sessionId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Instant old = Instant.now().minusSeconds(600);
        try {
            jdbc.update("INSERT INTO ai_sessions(id,project_id,actor_user_id,title,status,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,0)",
                    sessionId, "recovery-project", "recovery-user", "recovery", "ACTIVE", Timestamp.from(old), Timestamp.from(old));
            jdbc.update("INSERT INTO ai_agent_runs(id,session_id,project_id,actor_user_id,question,status,phase,evidence_json,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                    runId, sessionId, "recovery-project", "recovery-user", "recover", "RUNNING", "CONTEXT", "[]", Timestamp.from(old), Timestamp.from(old));

            assertThat(repository.recoverStaleRuns(Instant.now().minusSeconds(60))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM ai_agent_runs WHERE id=?", String.class, runId)).isEqualTo("RECOVERY_REQUIRED");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_steps WHERE run_id=? AND phase='RECOVERY'", Integer.class, runId)).isEqualTo(1);
            assertThat(repository.recoverStaleRuns(Instant.now().minusSeconds(60))).isZero();
        } finally {
            jdbc.update("DELETE FROM ai_agent_steps WHERE run_id=?", runId);
            jdbc.update("DELETE FROM ai_agent_runs WHERE id=?", runId);
            jdbc.update("DELETE FROM ai_sessions WHERE id=?", sessionId);
        }
    }
}
