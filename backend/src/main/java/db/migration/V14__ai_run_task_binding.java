package db.migration;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
public class V14__ai_run_task_binding extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql=context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN task_id VARCHAR(36) NULL");
            sql.execute("CREATE INDEX idx_ai_runs_task ON ai_agent_runs(task_id)");
        }
    }
}
