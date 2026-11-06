package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Runtime trace fields used for latency, reliability and cost reporting. */
public class V18__ai_agent_run_trace extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN started_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN finished_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_latency_ms BIGINT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN prompt_chars INT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN response_chars INT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_model VARCHAR(200) NULL");
        }
    }
}
