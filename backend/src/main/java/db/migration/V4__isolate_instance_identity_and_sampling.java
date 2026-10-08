package db.migration;

import java.sql.Connection;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 保留原主键及任务/日志外键；仅拆分 Agent 局部身份并补充采集证据。 */
public class V4__isolate_instance_identity_and_sampling extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean mysql = connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("MySQL");
        try (Statement sql = connection.createStatement()) {
            sql.execute("ALTER TABLE instances ADD COLUMN agent_instance_id VARCHAR(64)");
            sql.execute("UPDATE instances SET agent_instance_id=id");
            sql.execute(mysql
                    ? "ALTER TABLE instances MODIFY COLUMN agent_instance_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"
                    : "ALTER TABLE instances ALTER COLUMN agent_instance_id SET NOT NULL");
            sql.execute("CREATE UNIQUE INDEX uq_instances_node_agent ON instances(node_id,agent_instance_id)");
            sql.execute("ALTER TABLE instances ADD COLUMN last_seen_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE nodes ADD COLUMN last_checked_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE nodes ADD COLUMN last_successful_sync_at TIMESTAMP(6) NULL");
            sql.execute("ALTER TABLE nodes ADD COLUMN sync_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'");
            sql.execute("ALTER TABLE nodes ADD COLUMN sync_error_code VARCHAR(64) NULL");
            for (String table : new String[]{"nodes", "instances"}) {
                // 旧指标保留，明确标记 LEGACY/UNKNOWN，绝不伪造采样时间。
                sql.execute("ALTER TABLE " + table + " ADD COLUMN data_source VARCHAR(16) NOT NULL DEFAULT 'LEGACY'");
                sql.execute("ALTER TABLE " + table + " ADD COLUMN sampled_at TIMESTAMP(6) NULL");
                sql.execute("ALTER TABLE " + table + " ADD COLUMN metrics_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN'");
                nullable(sql, mysql, table, "cpu_percent", "DECIMAL(10,2)");
                nullable(sql, mysql, table, "memory_bytes", "BIGINT");
            }
            nullable(sql, mysql, "nodes", "memory_total_bytes", "BIGINT");
            nullable(sql, mysql, "instances", "player_count", "INT");
        }
    }

    private void nullable(Statement sql, boolean mysql, String table, String column, String type) throws Exception {
        if (mysql) {
            sql.execute("ALTER TABLE " + table + " MODIFY COLUMN " + column + " " + type + " NULL DEFAULT NULL");
        } else {
            sql.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP NOT NULL");
            sql.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " DROP DEFAULT");
        }
    }
}
