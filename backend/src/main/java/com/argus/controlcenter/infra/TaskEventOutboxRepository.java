package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 任务事件 Outbox；所有写入都由调用方当前事务包住。 */
@Repository
public class TaskEventOutboxRepository {
    public record Event(String id, String taskId, long sequence, TaskStatus from, TaskStatus to,
                        String actor, String reason, Instant occurredAt, int attempts) { }
    private final JdbcTemplate jdbc;
    public TaskEventOutboxRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(String taskId, long sequence, TaskStatus from, TaskStatus to,
                       String actor, String reason, Instant occurredAt) {
        jdbc.update("INSERT INTO task_event_outbox(id,task_id,event_sequence,from_status,to_status,actor,reason,occurred_at,status,attempts,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?,'PENDING',0,?,?)",
                UUID.randomUUID().toString(), taskId, sequence, from == null ? null : from.name(), to.name(), actor,
                reason, Timestamp.from(occurredAt), Timestamp.from(occurredAt), Timestamp.from(occurredAt));
    }

    public List<Event> claim(int limit, Instant now, Instant leaseUntil) {
        var selected = jdbc.query("SELECT id,task_id,event_sequence,from_status,to_status,actor,reason,occurred_at,attempts FROM task_event_outbox WHERE (status='PENDING' OR status='RETRY') AND next_attempt_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT " + Math.max(1, Math.min(limit, 100)),
                (rs, row) -> new Event(rs.getString("id"), rs.getString("task_id"), rs.getLong("event_sequence"),
                        rs.getString("from_status") == null ? null : TaskStatus.valueOf(rs.getString("from_status")),
                        TaskStatus.valueOf(rs.getString("to_status")), rs.getString("actor"), rs.getString("reason"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts")), Timestamp.from(now), Timestamp.from(now));
        var claimed = new java.util.ArrayList<Event>();
        for (Event event : selected) {
            if (jdbc.update("UPDATE task_event_outbox SET status='PROCESSING',lease_until=? WHERE id=? AND (status='PENDING' OR status='RETRY') AND (lease_until IS NULL OR lease_until<=?)",
                    Timestamp.from(leaseUntil), event.id(), Timestamp.from(now)) == 1) claimed.add(event);
        }
        return claimed;
    }

    public void markPublished(String id, Instant at) {
        jdbc.update("UPDATE task_event_outbox SET status='PUBLISHED',published_at=?,lease_until=NULL,last_error=NULL WHERE id=? AND status='PROCESSING'",
                Timestamp.from(at), id);
    }

    public void markRetry(String id, int attempts, Instant next, String error) {
        jdbc.update("UPDATE task_event_outbox SET status='RETRY',attempts=?,next_attempt_at=?,lease_until=NULL,last_error=? WHERE id=? AND status='PROCESSING'",
                attempts, Timestamp.from(next), error == null ? null : error.substring(0, Math.min(512, error.length())), id);
    }
}
