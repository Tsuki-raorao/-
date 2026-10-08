package db.migration;

import java.sql.*;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 只新增人工核对意图/队列/审计，绝不改写原UNKNOWN或已上线V5。 */
public class V6__task_manual_resolutions extends BaseJavaMigration {
    @Override public void migrate(Context context)throws Exception {
        Connection c=context.getConnection();String taskId=referenceType(c,"tasks");
        try(Statement sql=c.createStatement()) {
            sql.execute("CREATE TABLE task_resolutions (id VARCHAR(36) PRIMARY KEY, task_id "+taskId+" NOT NULL UNIQUE, request_key VARCHAR(36) NOT NULL UNIQUE, binding_json TEXT NOT NULL, intent_json TEXT NOT NULL, request_hash VARCHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, result_code VARCHAR(64) NOT NULL, attempts INT NOT NULL DEFAULT 0, cycle_attempts INT NOT NULL DEFAULT 0, version_no BIGINT NOT NULL, requested_by VARCHAR(32) NOT NULL, created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL, applied_at TIMESTAMP(6) NULL, receipt_json TEXT NULL, receipt_hash VARCHAR(64) NULL, CONSTRAINT fk_resolution_task FOREIGN KEY(task_id) REFERENCES tasks(id))");
            String resolutionId=referenceType(c,"task_resolutions");
            sql.execute("CREATE TABLE task_resolution_queue (resolution_id "+resolutionId+" PRIMARY KEY, next_run_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6) NULL, lease_owner VARCHAR(36) NULL, CONSTRAINT fk_review_queue_resolution FOREIGN KEY(resolution_id) REFERENCES task_resolutions(id))");
            sql.execute("CREATE INDEX idx_review_queue_due ON task_resolution_queue(next_run_at,lease_until)");
            sql.execute("CREATE TABLE task_resolution_events (id VARCHAR(36) PRIMARY KEY, resolution_id "+resolutionId+" NOT NULL, event_sequence BIGINT NOT NULL, from_status VARCHAR(16) NULL, to_status VARCHAR(16) NOT NULL, actor VARCHAR(32) NOT NULL, reason VARCHAR(64) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL, CONSTRAINT fk_review_event_resolution FOREIGN KEY(resolution_id) REFERENCES task_resolutions(id), CONSTRAINT uq_review_event_sequence UNIQUE(resolution_id,event_sequence))");
        }
    }
    private String referenceType(Connection c,String table)throws SQLException {
        if(!c.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL"))return table.equals("tasks")?"VARCHAR(64)":"VARCHAR(36)";
        try(PreparedStatement q=c.prepareStatement("SELECT DATA_TYPE,CHARACTER_MAXIMUM_LENGTH,CHARACTER_SET_NAME,COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME='id'")) {
            q.setString(1,table);
            try(ResultSet row=q.executeQuery()) {
                if(!row.next())throw new SQLException("missing resolution parent identity");
                String type=row.getString(1),charset=row.getString(3),collation=row.getString(4);long length=row.getLong(2);
                if(!(type.equals("varchar")||type.equals("char"))||length<1||length>65535||charset==null||!charset.matches("[A-Za-z0-9_]+")||collation==null||!collation.matches("[A-Za-z0-9_]+")||row.next())throw new SQLException("unsafe resolution identity metadata");
                return type+"("+length+") CHARACTER SET "+charset+" COLLATE "+collation;
            }
        }
    }
}
