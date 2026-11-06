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

/** Verifies retry parentage and retry-key lookup are durable and project scoped. */
@SpringBootTest
@ActiveProfiles("test")
class AiAgentRetryRepositoryTest {
    @Autowired private AiAgentRepository repository;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void retryRunRetainsParentAndRetryReason() {
        String session=UUID.randomUUID().toString(), parent=UUID.randomUUID().toString();
        String key=UUID.randomUUID().toString();
        Instant now=Instant.now();
        try {
            jdbc.update("INSERT INTO ai_sessions(id,project_id,actor_user_id,title,status,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,0)",session,"retry-project","retry-user","retry","ACTIVE",Timestamp.from(now),Timestamp.from(now));
            jdbc.update("INSERT INTO ai_agent_runs(id,session_id,project_id,actor_user_id,question,status,phase,evidence_json,created_at,updated_at,version_no) VALUES(?,?,?,?,?,?,?,?,?,?,0)",parent,session,"retry-project","retry-user","question","FAILED","FAILED","[]",Timestamp.from(now),Timestamp.from(now));
            var child=repository.createRun(session,"retry-project","retry-user","question",parent,key,"manual retry");
            assertThat(child.getRetryOfRunId()).isEqualTo(parent);
            assertThat(child.getRetryReason()).isEqualTo("manual retry");
            assertThat(repository.findRetryByKey("retry-project","retry-user",key)).hasValueSatisfying(found -> assertThat(found.getId()).isEqualTo(child.getId()));
            assertThat(repository.findRetryByKey("other-project","retry-user",key)).isEmpty();
        } finally {
            jdbc.update("DELETE FROM ai_agent_steps WHERE run_id IN (?,?)",parent, parent);
            jdbc.update("DELETE FROM ai_agent_runs WHERE retry_key=? OR id=?",key,parent);
            jdbc.update("DELETE FROM ai_sessions WHERE id=?",session);
        }
    }
}
