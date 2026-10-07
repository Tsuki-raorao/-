package com.argus.controlcenter.repository;

import com.argus.controlcenter.domain.Node;
import com.argus.controlcenter.domain.NodeStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.*;

/** 节点表访问层。所有方法直接使用参数化 SQL，避免字符串拼接注入。 */
@Repository
public class NodeRepository {
    private final JdbcTemplate jdbc;
    public NodeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** 将数据库的一行转换成接口返回的领域对象。 */
    private Node map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp heartbeat = rs.getTimestamp("last_heartbeat");
        return new Node(rs.getString("id"), rs.getString("name"), rs.getString("address"), NodeStatus.valueOf(rs.getString("status")), heartbeat == null ? null : heartbeat.toInstant(),
                rs.getDouble("cpu_percent"), rs.getLong("memory_bytes"), rs.getLong("memory_total_bytes"));
    }
    public List<Node> findAll() { return jdbc.query("SELECT id,name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes FROM nodes ORDER BY id", this::map); }
    public Optional<Node> findById(String id) { return jdbc.query("SELECT id,name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes FROM nodes WHERE id=?", this::map, id).stream().findFirst(); }
    public Node save(Node value) { Object heartbeat = value.getLastHeartbeat() == null ? null : Timestamp.from(value.getLastHeartbeat()); int updated=jdbc.update("UPDATE nodes SET name=?,address=?,status=?,last_heartbeat=?,cpu_percent=?,memory_bytes=?,memory_total_bytes=? WHERE id=?", value.getName(), value.getAddress(), value.getStatus().name(), heartbeat, value.getCpuPercent(), value.getMemoryBytes(), value.getMemoryTotalBytes(), value.getId()); if (updated == 0) jdbc.update("INSERT INTO nodes (id,name,address,status,last_heartbeat,cpu_percent,memory_bytes,memory_total_bytes) VALUES (?,?,?,?,?,?,?,?)", value.getId(), value.getName(), value.getAddress(), value.getStatus().name(), heartbeat, value.getCpuPercent(), value.getMemoryBytes(), value.getMemoryTotalBytes()); return value; }
    public long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM nodes", Long.class); }
    public int deleteById(String id) { return jdbc.update("DELETE FROM nodes WHERE id=?", id); }
}
