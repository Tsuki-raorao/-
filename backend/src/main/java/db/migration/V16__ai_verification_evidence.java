package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 保存 Agent 执行后的实例与日志复查证据。 */
public class V16__ai_verification_evidence extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_agent_runs ADD COLUMN verification_json TEXT NULL");
        }
    }
}
