package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 消费者收件记录与去重事实；独立于任务状态机。 */
public class V10__task_event_receipts extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var sql=context.getConnection().createStatement()) {
            sql.execute("CREATE TABLE task_event_receipts (event_id VARCHAR(128) PRIMARY KEY,task_id VARCHAR(64) NOT NULL,event_sequence BIGINT NOT NULL,event_status VARCHAR(16) NOT NULL,observed_status VARCHAR(16) NOT NULL,consumed_at TIMESTAMP(6) NOT NULL,CONSTRAINT uq_receipt_task_sequence UNIQUE(task_id,event_sequence))");
            sql.execute("CREATE INDEX idx_receipt_consumed ON task_event_receipts(consumed_at)");
        }
    }
}
