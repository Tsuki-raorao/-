package db.migration;

import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** AI Agent 会话、运行和步骤；所有记录绑定项目与操作者。 */
public class V13__ai_agent_runs extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try (Statement sql = context.getConnection().createStatement()) {
            sql.execute("CREATE TABLE ai_sessions (id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, actor_user_id VARCHAR(36) NOT NULL, title VARCHAR(200) NOT NULL, status VARCHAR(24) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version_no BIGINT NOT NULL DEFAULT 0)");
            sql.execute("CREATE INDEX idx_ai_sessions_scope ON ai_sessions(project_id,actor_user_id,updated_at)");
            sql.execute("CREATE TABLE ai_agent_runs (id VARCHAR(36) PRIMARY KEY, session_id VARCHAR(36) NOT NULL, project_id VARCHAR(36) NOT NULL, actor_user_id VARCHAR(36) NOT NULL, question VARCHAR(4000) NOT NULL, status VARCHAR(24) NOT NULL, phase VARCHAR(32) NOT NULL, answer TEXT NULL, evidence_json TEXT NOT NULL, action_plan_json TEXT NULL, error_code VARCHAR(64) NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, version_no BIGINT NOT NULL DEFAULT 0, CONSTRAINT fk_ai_run_session FOREIGN KEY(session_id) REFERENCES ai_sessions(id))");
            sql.execute("CREATE INDEX idx_ai_runs_scope ON ai_agent_runs(project_id,actor_user_id,created_at)");
            sql.execute("CREATE TABLE ai_agent_steps (id VARCHAR(36) PRIMARY KEY, run_id VARCHAR(36) NOT NULL, sequence_no INT NOT NULL, phase VARCHAR(32) NOT NULL, status VARCHAR(24) NOT NULL, input_json TEXT NOT NULL, output_json TEXT NULL, error_code VARCHAR(64) NULL, started_at TIMESTAMP(6) NOT NULL, finished_at TIMESTAMP(6) NULL, CONSTRAINT fk_ai_step_run FOREIGN KEY(run_id) REFERENCES ai_agent_runs(id), CONSTRAINT uq_ai_step_sequence UNIQUE(run_id,sequence_no))");
            sql.execute("CREATE INDEX idx_ai_steps_run ON ai_agent_steps(run_id,sequence_no)");
        }
    }
}
