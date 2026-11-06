package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Adds optional local embeddings used by deterministic hybrid retrieval. */
public class V22__ai_knowledge_embeddings extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("ALTER TABLE ai_knowledge_chunks ADD COLUMN embedding_json TEXT NULL");
            sql.execute("ALTER TABLE ai_knowledge_chunks ADD COLUMN embedding_model VARCHAR(64) NULL");
        }
    }
}
