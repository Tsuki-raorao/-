package db.migration;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Completes an interrupted provider-usage migration without duplicating columns. */
public class V21__ai_provider_usage extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        addIfMissing(context, "provider_prompt_tokens", "INT NULL");
        addIfMissing(context, "provider_completion_tokens", "INT NULL");
        addIfMissing(context, "provider_total_tokens", "INT NULL");
        addIfMissing(context, "provider_cost_micros", "BIGINT NULL");
    }

    private void addIfMissing(Context context, String column, String definition) throws Exception {
        DatabaseMetaData meta = context.getConnection().getMetaData();
        try (ResultSet columns = meta.getColumns(null, null, null, null)) {
            while (columns.next()) {
                if ("ai_agent_runs".equalsIgnoreCase(columns.getString("TABLE_NAME"))
                        && column.equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return;
            }
        }
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN " + column + " " + definition);
        }
    }
}
