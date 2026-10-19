package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/** 有界历史指标与可恢复告警，按项目隔离；不改写资源与任务事实。 */
public class V11__monitoring_alerts extends BaseJavaMigration {
    @Override public void migrate(Context context) throws Exception {
        try(var sql=context.getConnection().createStatement()) {
            sql.execute("CREATE TABLE monitor_alerts(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36) NOT NULL,rule_key VARCHAR(48) NOT NULL,resource_id VARCHAR(128) NOT NULL,title VARCHAR(128) NOT NULL,severity VARCHAR(16) NOT NULL,state VARCHAR(16) NOT NULL,observation_state VARCHAR(16) NOT NULL,observed_value DOUBLE NULL,threshold_value DOUBLE NOT NULL,summary VARCHAR(512) NOT NULL,opened_at TIMESTAMP(6) NULL,resolved_at TIMESTAMP(6) NULL,checked_at TIMESTAMP(6) NOT NULL,episodes INT NOT NULL DEFAULT 0,CONSTRAINT uq_monitor_rule_resource UNIQUE(project_id,rule_key,resource_id))");
            sql.execute("CREATE INDEX idx_monitor_alerts_project_state ON monitor_alerts(project_id,state,checked_at)");
            sql.execute("CREATE TABLE monitor_alert_events(id VARCHAR(36) PRIMARY KEY,alert_id VARCHAR(36) NOT NULL,project_id VARCHAR(36) NOT NULL,state VARCHAR(16) NOT NULL,metric_value DOUBLE NULL,summary VARCHAR(512) NOT NULL,occurred_at TIMESTAMP(6) NOT NULL,CONSTRAINT fk_monitor_event_alert FOREIGN KEY(alert_id) REFERENCES monitor_alerts(id))");
            sql.execute("CREATE INDEX idx_monitor_events_project_time ON monitor_alert_events(project_id,occurred_at)");
            sql.execute("CREATE TABLE monitor_samples(id VARCHAR(36) PRIMARY KEY,project_id VARCHAR(36) NOT NULL,resource_id VARCHAR(128) NOT NULL,metric VARCHAR(48) NOT NULL,metric_value DOUBLE NULL,observation_state VARCHAR(16) NOT NULL,source VARCHAR(24) NOT NULL,sampled_at TIMESTAMP(6) NOT NULL)");
            sql.execute("CREATE INDEX idx_monitor_samples_series ON monitor_samples(project_id,resource_id,metric,sampled_at,id)");
            sql.execute("CREATE INDEX idx_monitor_samples_retention ON monitor_samples(sampled_at)");
        }
    }
}
