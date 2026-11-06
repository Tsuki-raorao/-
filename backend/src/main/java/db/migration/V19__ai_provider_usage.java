package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Provider usage fields used for token and optional cost accounting. */
public class V19__ai_provider_usage extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_prompt_tokens INT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_completion_tokens INT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_total_tokens INT NULL");
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN provider_cost_micros BIGINT NULL");
        }
    }
}
