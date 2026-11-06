package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** Agent 会话记忆；记忆与项目、会话和操作者同时绑定。 */
public class V17__ai_agent_memory extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("CREATE TABLE ai_agent_memories (id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, project_id VARCHAR(36) NOT NULL, actor_user_id VARCHAR(36) NOT NULL, memory_type VARCHAR(24) NOT NULL, content TEXT NOT NULL, source_run_id VARCHAR(36) NULL, status VARCHAR(16) NOT NULL, created_at TIMESTAMP(6) NOT NULL, expires_at TIMESTAMP(6) NULL, CONSTRAINT fk_ai_memory_session FOREIGN KEY(session_id) REFERENCES ai_sessions(id), CONSTRAINT fk_ai_memory_run FOREIGN KEY(source_run_id) REFERENCES ai_agent_runs(id))");
            sql.execute("CREATE INDEX idx_ai_memory_scope ON ai_agent_memories(project_id,session_id,actor_user_id,status,created_at)");
        }
    }
}
