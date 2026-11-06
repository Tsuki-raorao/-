package com.argus.controlcenter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Durable, scoped memory used only as historical context for the project agent. */
@Repository
public class AiAgentMemoryRepository {
    private final JdbcTemplate jdbc;

    public AiAgentMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String sessionId, String projectId, String actorUserId,
                     String sourceRunId, String memoryType, String content) {
        jdbc.update("INSERT INTO ai_agent_memories(id,session_id,project_id,actor_user_id,memory_type,content,source_run_id,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), sessionId, projectId, actorUserId,
                memoryType, content, sourceRunId, "ACTIVE", Timestamp.from(Instant.now()), null);
    }

    public List<Map<String, Object>> latest(String sessionId, String projectId,
                                            String actorUserId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 50));
        String sql = "SELECT id,memory_type,content,source_run_id,created_at FROM ai_agent_memories "
                + "WHERE session_id=? AND project_id=? AND actor_user_id=? AND status='ACTIVE' "
                + "AND (expires_at IS NULL OR expires_at> CURRENT_TIMESTAMP) "
                + "ORDER BY created_at DESC LIMIT " + safeLimit;
        return jdbc.queryForList(sql, sessionId, projectId, actorUserId);
    }
}
