package db.migration;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
public class V15__ai_knowledge_base extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception { try(Statement sql=context.getConnection().createStatement()){
        sql.execute("CREATE TABLE ai_knowledge_documents (id VARCHAR(36) PRIMARY KEY, project_id VARCHAR(36) NOT NULL, title VARCHAR(200) NOT NULL, source_uri VARCHAR(500) NOT NULL, version VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, content_hash CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL)");
        sql.execute("CREATE UNIQUE INDEX uq_ai_knowledge_version ON ai_knowledge_documents(project_id,source_uri,version)");
        sql.execute("CREATE INDEX idx_ai_knowledge_scope ON ai_knowledge_documents(project_id,status,updated_at)");
        sql.execute("CREATE TABLE ai_knowledge_chunks (id VARCHAR(36) PRIMARY KEY, document_id VARCHAR(36) NOT NULL, project_id VARCHAR(36) NOT NULL, chunk_no INT NOT NULL, content TEXT NOT NULL, content_hash CHAR(64) NOT NULL, created_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_ai_chunk_document FOREIGN KEY(document_id) REFERENCES ai_knowledge_documents(id), CONSTRAINT uq_ai_chunk_no UNIQUE(document_id,chunk_no))");
        sql.execute("CREATE INDEX idx_ai_chunks_scope ON ai_knowledge_chunks(project_id,document_id,chunk_no)");
    }}
}
