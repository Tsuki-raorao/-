package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Records the parent run and idempotency key for an explicitly requested retry. */
public class V20__ai_run_retry_audit extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN retry_of_run_id VARCHAR(36) NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN retry_key VARCHAR(36) NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN retry_reason VARCHAR(500) NULL");
            sql.execute("CREATE INDEX idx_ai_runs_retry_parent ON ai_agent_runs(retry_of_run_id)");
            sql.execute("CREATE UNIQUE INDEX uq_ai_runs_retry_key ON ai_agent_runs(project_id,actor_user_id,retry_key)");
        }
    }
}
