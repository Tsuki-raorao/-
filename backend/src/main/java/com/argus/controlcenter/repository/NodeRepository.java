package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.argus.controlcenter.repository.JdbcValues.*;
import com.argus.controlcenter.identity.IdentityConstants;

/** 节点快照持久化；失败检查时间和成功心跳分别保存。 */
@Repository
public class NodeRepository {
    private static final String LEGACY_COLUMNS = "id,name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes,last_checked_at,last_successful_sync_at,sync_status,sync_error_code,data_source,sampled_at,metrics_status";
    private final JdbcTemplate jdbc;
    public NodeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Node map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Node value = new Node(rs.getString("id"), rs.getString("name"), rs.getString("address"),
                NodeStatus.valueOf(rs.getString("status")), instant(rs, "last_heartbeat"));
        value.setCpuPercent(decimal(rs, "cpu_percent"));
        value.setMemoryBytes(integer(rs, "memory_bytes"));
        value.setMemoryTotalBytes(integer(rs, "memory_total_bytes"));
        value.setLastCheckedAt(instant(rs, "last_checked_at"));
        value.setLastSuccessfulSyncAt(instant(rs, "last_successful_sync_at"));
        value.setSyncStatus(SyncStatus.valueOf(rs.getString("sync_status")));
        value.setSyncErrorCode(rs.getString("sync_error_code"));
        value.setDataSource(rs.getString("data_source"));
        value.setSampledAt(instant(rs, "sampled_at"));
        value.setMetricsStatus(MetricsStatus.valueOf(rs.getString("metrics_status")));
        try { value.setProjectId(rs.getString("project_id")); } catch (java.sql.SQLException ignored) { }
        return value;
    }
    private String columns(){return hasProjectColumn()?LEGACY_COLUMNS+",project_id":LEGACY_COLUMNS;}
    public List<Node> findAll() { return jdbc.query("SELECT " + columns() + " FROM nodes ORDER BY id", this::map); }
    public Optional<Node> findById(String id) { return jdbc.query("SELECT " + columns() + " FROM nodes WHERE id=?", this::map, id).stream().findFirst(); }
    /** 同步持久化与业务删除使用相同父行锁，避免清单写入和关联删除交错。 */
    public Optional<Node> findByIdForUpdate(String id) { return jdbc.query("SELECT " + columns() + " FROM nodes WHERE id=? FOR UPDATE", this::map, id).stream().findFirst(); }

    private Object[] fields(Node value) {
        return new Object[]{value.getName(), value.getAddress(), value.getStatus().name(), timestamp(value.getLastHeartbeat()),
                value.getCpuPercent(), value.getMemoryBytes(), value.getMemoryTotalBytes(), timestamp(value.getLastCheckedAt()),
                timestamp(value.getLastSuccessfulSyncAt()), value.getSyncStatus().name(), value.getSyncErrorCode(),
                value.getDataSource(), timestamp(value.getSampledAt()), value.getMetricsStatus().name()};
    }
    /** 采集只能更新现有节点；网络等待期间节点已删除时返回 false，绝不重新登记。 */
    public boolean updateExisting(Node value) {
        if(hasProjectColumn()) return jdbc.update("UPDATE nodes SET name=?,address=?,status=?,last_heartbeat=?,cpu_percent=?,memory_bytes=?,memory_total_bytes=?,last_checked_at=?,last_successful_sync_at=?,sync_status=?,sync_error_code=?,data_source=?,sampled_at=?,metrics_status=?,project_id=? WHERE id=?", appendId(withProject(value),value.getId())) > 0;
        return jdbc.update("UPDATE nodes SET name=?,address=?,status=?,last_heartbeat=?,cpu_percent=?,memory_bytes=?,memory_total_bytes=?,last_checked_at=?,last_successful_sync_at=?,sync_status=?,sync_error_code=?,data_source=?,sampled_at=?,metrics_status=? WHERE id=?", appendId(fields(value),value.getId())) > 0;
    }
    public Node save(Node value) {
        if (!updateExisting(value)) {
            if(hasProjectColumn()) jdbc.update("INSERT INTO nodes (name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes,last_checked_at,last_successful_sync_at,sync_status,sync_error_code,data_source,sampled_at,metrics_status,project_id,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", appendId(withProject(value),value.getId()));
            else jdbc.update("INSERT INTO nodes (name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes,last_checked_at,last_successful_sync_at,sync_status,sync_error_code,data_source,sampled_at,metrics_status,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", appendId(fields(value),value.getId()));
        }
        return value;
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM nodes", Long.class); }
    public int deleteById(String id) { return jdbc.update("DELETE FROM nodes WHERE id=?", id); }
    private Object[] withProject(Node value){Object[] base=fields(value);Object[] out=java.util.Arrays.copyOf(base,base.length+1);out[out.length-1]=value.getProjectId()==null?IdentityConstants.LEGACY_PROJECT_ID:value.getProjectId();return out;}
    private Object[] appendId(Object[] values,String id){Object[] out=java.util.Arrays.copyOf(values,values.length+1);out[out.length-1]=id;return out;}
    private boolean hasProjectColumn(){try{return jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='NODES' AND COLUMN_NAME='PROJECT_ID'",Integer.class)>0;}catch(RuntimeException ignored){return false;}}
}
