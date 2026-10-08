package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.*;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 整份有效清单与成功标记同事务提交，数据库异常不会留下半份快照。 */
@Service
public class AgentSnapshotStore {
    private final InstanceRepository instances;
    private final NodeRepository nodes;
    public AgentSnapshotStore(InstanceRepository instances, NodeRepository nodes) { this.instances = instances; this.nodes = nodes; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void save(Node node, List<Instance> snapshot, Instant completedAt) {
        // HTTP 请求不持有数据库锁；返回后确认节点仍存在，再与删除操作按相同父行锁顺序串行。
        if (nodes.findByIdForUpdate(node.getId()).isEmpty()) return;
        for (Instance value : snapshot) {
            // 同一批观测时间与节点成功时间完全一致，前端据此判断完整清单里已消失的历史实例。
            value.setLastSeenAt(completedAt);
            Instance previous = instances.findByNodeAndAgentId(node.getId(), value.getAgentInstanceId()).orElse(null);
            if (previous != null) {
                if (value.getVersion().equalsIgnoreCase("unknown")) value.setVersion(previous.getVersion());
                // 旧协议仍能提供身份/状态，但不能覆盖已有可信指标或伪造新采样时间。
                if (value.getDataSource().equals("LEGACY")) {
                    value.setCpuPercent(previous.getCpuPercent());
                    value.setMemoryBytes(previous.getMemoryBytes());
                    value.setPlayerCount(previous.getPlayerCount());
                    value.setSampledAt(previous.getSampledAt());
                }
            }
            instances.saveDiscovered(value);
        }
        node.setLastSuccessfulSyncAt(completedAt);
        node.setSyncStatus(SyncStatus.OK);
        node.setSyncErrorCode(null);
        if (!nodes.updateExisting(node)) throw new IllegalStateException("snapshot node disappeared");
    }
}
