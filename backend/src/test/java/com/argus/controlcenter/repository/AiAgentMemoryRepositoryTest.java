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

/** Verifies durable agent memory is isolated by project, session and actor. */
@SpringBootTest
@ActiveProfiles("test")
class AiAgentMemoryRepositoryTest {
    @Autowired private AiAgentMemoryRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void latestMemoryCannotCrossProjectSessionOrActorAndOmitsExpiredRows() {
        String session = UUID.randomUUID().toString();
        String otherSession = UUID.randomUUID().toString();
        try {
            insertSession(session, "memory-project", "memory-user");
            insertSession(otherSession, "other-project", "memory-user");

            repository.save(session, "memory-project", "memory-user", null, "RUN_SUMMARY", "visible");
            repository.save(otherSession, "other-project", "memory-user", null, "RUN_SUMMARY", "other-project");
            String expiredId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO ai_agent_memories(id,session_id,project_id,actor_user_id,memory_type,content,source_run_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    expiredId, session, "memory-project", "memory-user", "RUN_SUMMARY", "expired", null, "ACTIVE",
                    Timestamp.from(Instant.now().minusSeconds(30)), Timestamp.from(Instant.now().minusSeconds(1)));

            assertThat(repository.latest(session, "memory-project", "memory-user", 10))
                    .extracting(row -> row.get("CONTENT"))
                    .contains("visible")
                    .doesNotContain("other-project", "expired");
            assertThat(repository.latest(session, "other-project", "memory-user", 10)).isEmpty();
            assertThat(repository.latest(session, "memory-project", "other-user", 10)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM ai_agent_memories WHERE session_id IN (?,?)", session, otherSession);
            jdbc.update("DELETE FROM ai_sessions WHERE id IN (?,?)", session, otherSession);
        }
    }

    private void insertSession(String id, String project, String actor) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO ai_sessions(id,project_id,actor_user_id,title,status,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,0)",
                id, project, actor, "memory-test", "ACTIVE", Timestamp.from(now), Timestamp.from(now));
    }
}
