package com.argus.controlcenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/** Deletes only expired evidence and terminal runs; active approval/recovery work is retained. */
@Repository
public class AiAgentRetentionRepository {
    private static final String TERMINAL = "('COMPLETED','EXECUTION_FAILED','FAILED')";
    private final JdbcTemplate jdbc;

    public AiAgentRetentionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int purge(Instant cutoff, int batchSize) {
        int limit = Math.max(10, Math.min(batchSize, 2000));
        int deleted = purgeTerminalRuns(cutoff, limit);
        deleted += purgeOldMemory(cutoff, limit);
        return deleted;
    }

    private int purgeTerminalRuns(Instant cutoff, int limit) {
        List<String> ids = jdbc.query("SELECT r.id FROM ai_agent_runs r WHERE r.status IN " + TERMINAL
                        + " AND r.updated_at<? AND NOT EXISTS (SELECT 1 FROM ai_agent_runs child "
                        + "WHERE child.retry_of_run_id=r.id AND child.status NOT IN " + TERMINAL + ") "
                        + "ORDER BY r.updated_at LIMIT " + limit,
                (rs, n) -> rs.getString("id"), Timestamp.from(cutoff));
        int deleted = 0;
        for (String id : ids) {
            jdbc.update("DELETE FROM ai_agent_memories WHERE source_run_id=?", id);
            jdbc.update("DELETE FROM ai_agent_steps WHERE run_id=?", id);
            deleted += jdbc.update("DELETE FROM ai_agent_runs WHERE id=? AND status IN " + TERMINAL, id);
        }
        return deleted;
    }

    private int purgeOldMemory(Instant cutoff, int limit) {
        List<String> ids = jdbc.query("SELECT m.id FROM ai_agent_memories m LEFT JOIN ai_agent_runs r ON r.id=m.source_run_id "
                        + "WHERE m.created_at<? AND (m.source_run_id IS NULL OR r.status IN " + TERMINAL + ") "
                        + "ORDER BY m.created_at LIMIT " + limit,
                (rs, n) -> rs.getString("id"), Timestamp.from(cutoff));
        int deleted = 0;
        for (String id : ids) deleted += jdbc.update("DELETE FROM ai_agent_memories WHERE id=?", id);
        return deleted;
    }
}
