package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.dto.CreateNodeRequest;
import com.argus.controlcenter.exception.NotFoundException;
import com.argus.controlcenter.exception.TaskControlException;
import com.argus.controlcenter.repository.NodeRepository;
import com.argus.controlcenter.repository.InstanceRepository;
import com.argus.controlcenter.repository.TaskRepository;
import com.argus.controlcenter.repository.LogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.UUID;

@Service
public class NodeService {
    private final NodeRepository repository;
    private final InstanceRepository instances;
    private final TaskRepository tasks;
    private final LogRepository logs;
    public NodeService(NodeRepository repository, InstanceRepository instances, TaskRepository tasks, LogRepository logs) {
        this.repository=repository; this.instances=instances; this.tasks=tasks; this.logs=logs;
    }
    public List<Node> findAll() { return repository.findAll(); }
    public Node findById(String id) { return repository.findById(id).orElseThrow(() -> new NotFoundException("node not found: " + id)); }
    public Node create(CreateNodeRequest request) { return repository.save(new Node(UUID.randomUUID().toString(), request.getName(), request.getAddress(), NodeStatus.UNKNOWN, null)); }
    public Node heartbeat(String id, NodeStatus status) {
        Node n = findById(id);
        n.setStatus(status == null ? NodeStatus.ONLINE : status);
        if (n.getStatus() == NodeStatus.ONLINE) n.setLastHeartbeat(Instant.now());
        return repository.save(n);
    }
    /** 保存 Agent 心跳和主机资源快照，资源值由只读同步链路提供。 */
    public Node heartbeat(String id, NodeStatus status, double cpuPercent, long memoryBytes, long memoryTotalBytes) {
        Node n = findById(id);
        n.setStatus(status == null ? NodeStatus.ONLINE : status);
        if (n.getStatus() == NodeStatus.ONLINE) n.setLastHeartbeat(Instant.now());
        n.setCpuPercent(cpuPercent);
        n.setMemoryBytes(memoryBytes);
        n.setMemoryTotalBytes(memoryTotalBytes);
        return repository.save(n);
    }
    public long count() { return repository.count(); }
    /** 只删除无任务历史的节点；任何历史任务（包括旧演示）均保护审计不被级联清除。 */
    @Transactional
    public void delete(String id) {
        repository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("node not found: " + id));
        if (tasks.hasNodeHistory(id)) throw new TaskControlException(409,"NODE_HAS_TASK_HISTORY");
        for (String instanceId : instances.findIdsByNodeId(id)) {
            logs.deleteByInstanceId(instanceId);
        }
        instances.deleteByNodeId(id);
        repository.deleteById(id);
    }
    public void seed() {
        if (findAll().isEmpty()) {
            Node node = new Node("node-local", "Local node", "127.0.0.1", NodeStatus.ONLINE, Instant.now(), 0, 0, 0);
            node.setDataSource("MOCK");
            node.setMetricsStatus(MetricsStatus.AVAILABLE);
            node.setSampledAt(Instant.now());
            repository.save(node);
        }
    }
}
