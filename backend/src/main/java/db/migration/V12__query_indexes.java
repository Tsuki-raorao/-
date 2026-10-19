package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Match the measured project log ordering and recent failure counting predicates. */
public class V12__query_indexes extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (var sql = context.getConnection().createStatement()) {
            sql.execute("CREATE INDEX idx_logs_project_time ON logs(project_id,log_timestamp)");
            sql.execute("CREATE INDEX idx_tasks_project_status_updated ON tasks(project_id,status,updated_at)");
            sql.execute("CREATE INDEX idx_tasks_project_created_order ON tasks(project_id,created_at DESC,id ASC)");
        }
    }
}
