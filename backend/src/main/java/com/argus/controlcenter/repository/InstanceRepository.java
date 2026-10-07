package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.Instance;
import com.argus.controlcenter.domain.InstanceStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

/** 服务实例表访问层。实例状态的业务校验由 InstanceService 负责。 */
@Repository
public class InstanceRepository {
    private final JdbcTemplate jdbc;
    public InstanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 将 instances 表的一行转换为 Instance。 */
    private Instance map(java.sql.ResultSet rs, int row) throws java.sql.SQLException { return new Instance(rs.getString("id"), rs.getString("name"), rs.getString("node_id"), rs.getString("container_name"), rs.getString("version"), InstanceStatus.valueOf(rs.getString("status")), rs.getTimestamp("updated_at").toInstant(), rs.getDouble("cpu_percent"), rs.getLong("memory_bytes"), rs.getInt("player_count")); }
    public List<Instance> findAll() { return jdbc.query("SELECT id,name,node_id,container_name,version,status,updated_at,cpu_percent,memory_bytes,player_count FROM instances ORDER BY id", this::map); }
    public Optional<Instance> findById(String id) { return jdbc.query("SELECT id,name,node_id,container_name,version,status,updated_at,cpu_percent,memory_bytes,player_count FROM instances WHERE id=?", this::map, id).stream().findFirst(); }
    public Instance save(Instance value) { int updated=jdbc.update("UPDATE instances SET name=?,node_id=?,container_name=?,version=?,status=?,updated_at=?,cpu_percent=?,memory_bytes=?,player_count=? WHERE id=?", value.getName(), value.getNodeId(), value.getContainerName(), value.getVersion(), value.getStatus().name(), Timestamp.from(value.getUpdatedAt()), value.getCpuPercent(), value.getMemoryBytes(), value.getPlayerCount(), value.getId()); if (updated == 0) jdbc.update("INSERT INTO instances (id,name,node_id,container_name,version,status,updated_at,cpu_percent,memory_bytes,player_count) VALUES (?,?,?,?,?,?,?,?,?,?)", value.getId(), value.getName(), value.getNodeId(), value.getContainerName(), value.getVersion(), value.getStatus().name(), Timestamp.from(value.getUpdatedAt()), value.getCpuPercent(), value.getMemoryBytes(), value.getPlayerCount()); return value; }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM instances", Long.class); }
    /** 删除某个节点的实例；节点删除前调用，满足外键约束。 */
    public int deleteByNodeId(String nodeId) { return jdbc.update("DELETE FROM instances WHERE node_id=?", nodeId); }
    public List<String> findIdsByNodeId(String nodeId) { return jdbc.queryForList("SELECT id FROM instances WHERE node_id=?", String.class, nodeId); }
}
