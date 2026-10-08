package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 保留旧任务和外键，仅将旧数据库演示任务标为 LEGACY_MOCK；绝不生成历史投递。 */
public class V5__durable_task_queue extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        try (Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE tasks ADD COLUMN node_id VARCHAR(64) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN agent_instance_id VARCHAR(64) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN command_id VARCHAR(36) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN idempotency_key VARCHAR(36) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN execution_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY_MOCK'");
            sql.execute("ALTER TABLE tasks ADD COLUMN requested_by VARCHAR(32) NOT NULL DEFAULT 'legacy'");
            sql.execute("ALTER TABLE tasks ADD COLUMN updated_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN attempts INT NOT NULL DEFAULT 0");
            sql.execute("ALTER TABLE tasks ADD COLUMN result_code VARCHAR(64) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN target_address VARCHAR(512) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN agent_node_id VARCHAR(128) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN store_id VARCHAR(128) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN expires_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE tasks ADD COLUMN version_no BIGINT NOT NULL DEFAULT 0");
            sql.execute("ALTER TABLE tasks ADD COLUMN agent_accepted BOOLEAN NOT NULL DEFAULT FALSE");
            sql.execute("UPDATE tasks SET node_id=(SELECT node_id FROM instances WHERE instances.id=tasks.instance_id), agent_instance_id=(SELECT agent_instance_id FROM instances WHERE instances.id=tasks.instance_id), updated_at=COALESCE(finished_at,created_at), result_code='LEGACY_MOCK'");
            sql.execute("CREATE UNIQUE INDEX uq_tasks_command ON tasks(command_id)");
            sql.execute("CREATE UNIQUE INDEX uq_tasks_idempotency ON tasks(idempotency_key)");
            sql.execute("CREATE INDEX idx_tasks_node ON tasks(node_id)");
            sql.execute("CREATE INDEX idx_tasks_created ON tasks(created_at,id)");
            sql.execute("CREATE TABLE task_queue (task_id VARCHAR(64) PRIMARY KEY, next_run_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6) NULL, lease_owner VARCHAR(36) NULL, CONSTRAINT fk_queue_task FOREIGN KEY (task_id) REFERENCES tasks(id))");
            sql.execute("CREATE INDEX idx_queue_due ON task_queue(next_run_at,lease_until)");
            sql.execute("CREATE TABLE instance_task_locks (instance_id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL UNIQUE, CONSTRAINT fk_task_lock_instance FOREIGN KEY(instance_id) REFERENCES instances(id), CONSTRAINT fk_task_lock_task FOREIGN KEY(task_id) REFERENCES tasks(id))");
            sql.execute("CREATE TABLE task_events (id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(64) NOT NULL, event_sequence BIGINT NOT NULL, from_status VARCHAR(16) NULL, to_status VARCHAR(16) NOT NULL, actor VARCHAR(32) NOT NULL, reason VARCHAR(64) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_event_task FOREIGN KEY(task_id) REFERENCES tasks(id), CONSTRAINT uq_event_sequence UNIQUE(task_id,event_sequence))");
        }
    }
}
