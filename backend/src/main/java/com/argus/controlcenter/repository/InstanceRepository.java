package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;
import static com.argus.controlcenter.repository.JdbcValues.*;

/** 中央 ID 关联历史记录，节点与 Agent 局部 ID 的复合唯一键负责发现去重。 */
@Repository
public class InstanceRepository {
    private static final String COLUMNS = "id,agent_instance_id,name,node_id,container_name,version,status,updated_at,cpu_percent,memory_bytes,player_count,last_seen_at,data_source,sampled_at,metrics_status";
    private final JdbcTemplate jdbc;
    public InstanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private Instance map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Instance value = new Instance(rs.getString("id"), rs.getString("name"), rs.getString("node_id"),
                rs.getString("container_name"), rs.getString("version"), InstanceStatus.valueOf(rs.getString("status")), instant(rs, "updated_at"));
        value.setAgentInstanceId(rs.getString("agent_instance_id"));
        value.setCpuPercent(decimal(rs, "cpu_percent"));
        value.setMemoryBytes(integer(rs, "memory_bytes"));
        Long players = integer(rs, "player_count");
        value.setPlayerCount(players == null ? null : players.intValue());
        value.setLastSeenAt(instant(rs, "last_seen_at"));
        value.setDataSource(rs.getString("data_source"));
        value.setSampledAt(instant(rs, "sampled_at"));
        value.setMetricsStatus(MetricsStatus.valueOf(rs.getString("metrics_status")));
        return value;
    }
    public List<Instance> findAll() { return jdbc.query("SELECT " + COLUMNS + " FROM instances ORDER BY id", this::map); }
    public Optional<Instance> findById(String id) { return jdbc.query("SELECT " + COLUMNS + " FROM instances WHERE id=?", this::map, id).stream().findFirst(); }
    public Optional<Instance> findByNodeAndAgentId(String nodeId, String agentId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM instances WHERE node_id=? AND agent_instance_id=?", this::map, nodeId, agentId).stream().findFirst();
    }

    public Instance save(Instance value) {
        Object[] fields = {value.getAgentInstanceId(), value.getName(), value.getNodeId(), value.getContainerName(), value.getVersion(),
                value.getStatus().name(), timestamp(value.getUpdatedAt()), value.getCpuPercent(), value.getMemoryBytes(), value.getPlayerCount(),
                timestamp(value.getLastSeenAt()), value.getDataSource(), timestamp(value.getSampledAt()), value.getMetricsStatus().name(), value.getId()};
        int updated = jdbc.update("UPDATE instances SET agent_instance_id=?,name=?,node_id=?,container_name=?,version=?,status=?,updated_at=?,cpu_percent=?,memory_bytes=?,player_count=?,last_seen_at=?,data_source=?,sampled_at=?,metrics_status=? WHERE id=?", fields);
        if (updated == 0) jdbc.update("INSERT INTO instances (agent_instance_id,name,node_id,container_name,version,status,updated_at,cpu_percent,memory_bytes,player_count,last_seen_at,data_source,sampled_at,metrics_status,id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", fields);
        return value;
    }

    /** 并发首次发现由唯一键兜底；保留获胜者 ID，绝不重新分配历史身份。 */
    public Instance saveDiscovered(Instance value) {
        findByNodeAndAgentId(value.getNodeId(), value.getAgentInstanceId()).ifPresent(previous -> value.setId(previous.getId()));
        try { return save(value); }
        catch (DuplicateKeyException conflict) {
            Instance winner = findByNodeAndAgentId(value.getNodeId(), value.getAgentInstanceId()).orElseThrow(() -> conflict);
            value.setId(winner.getId());
            return save(value);
        }
    }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM instances", Long.class); }
    public int deleteByNodeId(String nodeId) { return jdbc.update("DELETE FROM instances WHERE node_id=?", nodeId); }
    public List<String> findIdsByNodeId(String nodeId) { return jdbc.queryForList("SELECT id FROM instances WHERE node_id=?", String.class, nodeId); }
}