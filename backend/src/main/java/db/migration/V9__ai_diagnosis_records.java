package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** AI 只读诊断事实，保存回答与证据索引，不保存模型密钥。 */
public class V9__ai_diagnosis_records extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("CREATE TABLE ai_diagnoses (id VARCHAR(36) PRIMARY KEY, question VARCHAR(4000) NOT NULL, model VARCHAR(128) NOT NULL, answer TEXT NOT NULL, evidence_json TEXT NOT NULL, created_at TIMESTAMP(6) NOT NULL)");
            sql.execute("CREATE INDEX idx_ai_diagnoses_created ON ai_diagnoses(created_at)");
        }
    }
}
