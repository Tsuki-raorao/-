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

/** Retention removes terminal evidence while preserving approval/recovery work. */
@SpringBootTest
@ActiveProfiles("test")
class AiAgentRetentionRepositoryTest {
    @Autowired private AiAgentRetentionRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void terminalRunsArePurgedButWaitingApprovalAndRecoveryAreRetained() {
        Instant old = Instant.now().minusSeconds(3600);
        String terminalSession = UUID.randomUUID().toString();
        String pendingSession = UUID.randomUUID().toString();
        String terminalRun = UUID.randomUUID().toString();
        String pendingRun = UUID.randomUUID().toString();
        try {
            insertSession(terminalSession, old);
            insertSession(pendingSession, old);
            insertRun(terminalRun, terminalSession, "COMPLETED", old);
            insertRun(pendingRun, pendingSession, "WAITING_APPROVAL", old);
            jdbc.update("INSERT INTO ai_agent_steps(id,run_id,sequence_no,phase,status,input_json,output_json,started_at,finished_at) VALUES(?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), terminalRun, 1, "PROPOSAL", "COMPLETED", "{}", "{}", Timestamp.from(old), Timestamp.from(old));
            jdbc.update("INSERT INTO ai_agent_memories(id,session_id,project_id,actor_user_id,memory_type,content,source_run_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), terminalSession, "retention-project", "retention-user", "RUN_SUMMARY", "terminal", terminalRun, "ACTIVE", Timestamp.from(old), null);
            jdbc.update("INSERT INTO ai_agent_memories(id,session_id,project_id,actor_user_id,memory_type,content,source_run_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), pendingSession, "retention-project", "retention-user", "RUN_SUMMARY", "pending", pendingRun, "ACTIVE", Timestamp.from(old), null);

            assertThat(repository.purge(Instant.now().minusSeconds(300), 50)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_runs WHERE id=?", Integer.class, terminalRun)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_steps WHERE run_id=?", Integer.class, terminalRun)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_memories WHERE source_run_id=?", Integer.class, terminalRun)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM ai_agent_runs WHERE id=?", String.class, pendingRun)).isEqualTo("WAITING_APPROVAL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_agent_memories WHERE source_run_id=?", Integer.class, pendingRun)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM ai_agent_memories WHERE source_run_id IN (?,?)", terminalRun, pendingRun);
            jdbc.update("DELETE FROM ai_agent_steps WHERE run_id IN (?,?)", terminalRun, pendingRun);
            jdbc.update("DELETE FROM ai_agent_runs WHERE id IN (?,?)", terminalRun, pendingRun);
            jdbc.update("DELETE FROM ai_sessions WHERE id IN (?,?)", terminalSession, pendingSession);
        }
    }

    private void insertSession(String id, Instant created) {
        jdbc.update("INSERT INTO ai_sessions(id,project_id,actor_user_id,title,status,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,0)",
                id, "retention-project", "retention-user", "retention", "ACTIVE", Timestamp.from(created), Timestamp.from(created));
    }

    private void insertRun(String id, String session, String status, Instant created) {
        jdbc.update("INSERT INTO ai_agent_runs(id,session_id,project_id,actor_user_id,question,status,phase,evidence_json,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                id, session, "retention-project", "retention-user", "retention", status, "PROPOSAL", "[]", Timestamp.from(created), Timestamp.from(created));
    }
}
