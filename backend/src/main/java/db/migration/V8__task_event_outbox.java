package db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 将任务事件与业务事务一起落库，供消息发布器可靠重试。 */
public class V8__task_event_outbox extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        Connection c = context.getConnection();
        String taskId = referenceType(c, "tasks");
        try (Statement sql = c.createStatement()) {
            sql.execute("CREATE TABLE task_event_outbox (" +
                    "id VARCHAR(36) PRIMARY KEY, task_id " + taskId + " NOT NULL, " +
                    "event_sequence BIGINT NOT NULL, from_status VARCHAR(16) NULL, " +
                    "to_status VARCHAR(16) NOT NULL, actor VARCHAR(32) NOT NULL, " +
                    "reason VARCHAR(64) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL, " +
                    "status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0, " +
                    "next_attempt_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6) NULL, " +
                    "last_error VARCHAR(512) NULL, published_at TIMESTAMP(6) NULL, " +
                    "created_at TIMESTAMP(6) NOT NULL, " +
                    "CONSTRAINT fk_outbox_task FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE, " +
                    "CONSTRAINT uq_outbox_task_sequence UNIQUE(task_id,event_sequence))");
            sql.execute("CREATE INDEX idx_outbox_due ON task_event_outbox(status,next_attempt_at,lease_until)");
        }
    }

    private String referenceType(Connection c, String table) throws SQLException {
        if (!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL")) return "VARCHAR(64)";
        try (var q = c.prepareStatement("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='id'")) {
            q.setString(1, table);
            try (var row = q.executeQuery()) {
                if (!row.next()) throw new SQLException("missing outbox parent identity");
                String type = row.getString(1), charset = row.getString(3), collation = row.getString(4);
                long length = row.getLong(2);
                if (!(type.equals("varchar") || type.equals("char")) || length < 1 || length > 65535
                        || charset == null || !charset.matches("[A-Za-z0-9_]+")
                        || collation == null || !collation.matches("[A-Za-z0-9_]+") || row.next())
                    throw new SQLException("unsafe outbox identity metadata");
                return type + "(" + length + ") CHARACTER SET " + charset + " COLLATE " + collation;
            }
        }
    }
}
