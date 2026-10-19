package com.argus.controlcenter.service;

import com.argus.controlcenter.config.AgentGatewayProperties;
import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.repository.NodeRepository;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 只读采集：先验证完整清单，再原子持久化；失败保留上次成功证据。 */
@Service
public class AgentSnapshotSyncService {
    private final NodeRepository nodes;
    private final AgentGatewayService gateway;
    private final AgentGatewayProperties properties;
    private final AgentSnapshotStore store;
    private final AgentSnapshotParser parser = new AgentSnapshotParser();
    private static final Logger log = LoggerFactory.getLogger(AgentSnapshotSyncService.class);
    private final ExecutorService workers;
    private final int concurrency;
    private final ReentrantLock roundLock = new ReentrantLock();
    private volatile boolean closing;
    // Fixed stripes avoid an ever-growing map of deleted node identities.
    private final ReentrantLock[] nodeLocks = new ReentrantLock[128];

    public AgentSnapshotSyncService(NodeRepository nodes, AgentGatewayService gateway,
                                    AgentGatewayProperties properties, AgentSnapshotStore store) {
        this.nodes = nodes; this.gateway = gateway; this.properties = properties; this.store = store;
        concurrency = properties.getSyncConcurrency();
        workers = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(concurrency), runnable -> {
                    Thread thread = new Thread(runnable, "argus-node-sync"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        for (int i=0;i<nodeLocks.length;i++) nodeLocks[i]=new ReentrantLock();
    }

    @Scheduled(initialDelayString = "${argus.agent.sync-initial-delay-ms:5000}",
            fixedDelayString = "${argus.agent.sync-interval-ms:60000}")
    public void syncAll() {
        if (closing || !properties.isEnabled() || !properties.isReadOnly()) return;
        if (!roundLock.tryLock()) return;
        try {
            var pending = nodes.findAll().iterator();
            var completed = new ExecutorCompletionService<Void>(workers);
            int inFlight = 0;
            while (pending.hasNext() || inFlight > 0) {
                while (pending.hasNext() && inFlight < concurrency) {
                    String id = pending.next().getId();
                    completed.submit(() -> { syncNode(id); return null; }); inFlight++;
                }
                if (inFlight > 0) {
                    try { completed.take().get(); }
                    catch (ExecutionException failed) {
                        // Keep the rest of the round progressing; do not log remote response/secrets.
                        log.warn("node collection failed: {}", failed.getCause().getClass().getSimpleName());
                    }
                    inFlight--;
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally { roundLock.unlock(); }
    }

    /** Different nodes may overlap; the same node never has concurrent snapshot writes. */
    public void syncNode(String nodeId) {
        if (closing || !properties.isEnabled() || !properties.isReadOnly()) return;
        ReentrantLock lock=nodeLocks[Math.floorMod(nodeId.hashCode(),nodeLocks.length)];
        try { lock.lockInterruptibly(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
        try { if (!closing) syncLocked(nodeId); } finally { lock.unlock(); }
    }

    @PreDestroy public void close() { closing=true; workers.shutdownNow(); }

    private void syncLocked(String nodeId) {
        Node node = nodes.findById(nodeId).orElse(null);
        if (node == null) return;
        node.setLastCheckedAt(Instant.now());
        if (!nodes.updateExisting(node)) return;
        AgentSnapshotParser.Sample health;
        try { health = parser.health(gateway.health(nodeId)); }
        catch (IllegalArgumentException invalid) { fail(node, false, "INVALID_HEALTH"); return; }
        catch (RuntimeException failed) { fail(node, false, "HEALTH_REQUEST_FAILED"); return; }

        node.setStatus(NodeStatus.ONLINE);
        node.setLastHeartbeat(Instant.now());
        if (!health.source().equals("LEGACY")) {
            node.setCpuPercent(health.cpu());
            node.setMemoryBytes(health.memory());
            node.setMemoryTotalBytes(health.total());
            node.setSampledAt(health.at());
        }
        node.setDataSource(health.source());
        node.setMetricsStatus(health.status());
        node.setSyncStatus(SyncStatus.PARTIAL);
        node.setSyncErrorCode(null);
        if (!nodes.updateExisting(node)) return;

        java.util.List<Instance> snapshot;
        try { snapshot = parser.instances(nodeId, gateway.instances(nodeId), Instant.now()); }
        catch (IllegalArgumentException invalid) { fail(node, true, "INVALID_INSTANCES"); return; }
        catch (RuntimeException failed) { fail(node, true, "INSTANCES_REQUEST_FAILED"); return; }
        try { store.save(node, snapshot, Instant.now()); }
        catch (RuntimeException failed) {
            // store 的事务已回滚；恢复内存对象中的成功时间，避免失败标记把它带回库。
            nodes.findById(nodeId).ifPresent(previous -> fail(previous, true, "PERSISTENCE_FAILED"));
        }
    }

    private void fail(Node node, boolean healthSucceeded, String code) {
        node.setStatus(healthSucceeded ? NodeStatus.ONLINE : NodeStatus.OFFLINE);
        node.setSyncStatus(healthSucceeded ? SyncStatus.PARTIAL : SyncStatus.FAILED);
        node.setSyncErrorCode(code);
        nodes.updateExisting(node);
    }
}
