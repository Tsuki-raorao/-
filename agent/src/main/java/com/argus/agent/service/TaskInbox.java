package com.argus.agent.service;

import com.argus.agent.Json;
import com.argus.agent.model.TaskCommand;
import com.argus.agent.model.TaskView;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 独占目录中的任务 Inbox。只有文件 force 和原子替换成功后才更新内存视图。 */
public final class TaskInbox implements AutoCloseable {
    public record Entry(TaskCommand command, String targetContainer, TaskView task) {
        public Entry transition(String status, String code, String observed) {
            String now = Instant.now().toString();
            return new Entry(command, targetContainer, new TaskView(task.taskId(), task.instanceId(), task.action(), status, code,
                    task.createdAt(), status.equals("RUNNING") ? now : task.startedAt(),
                    Set.of("SUCCEEDED", "FAILED", "UNKNOWN").contains(status) ? now : null,
                    task.executionMode(), task.nodeId(), task.storeId(), code, observed));
        }
    }
    private static final int MAGIC = 0x41524731;
    private final Path directory;
    private final int capacity;
    private final Map<String, Entry> records = new LinkedHashMap<>();
    private FileChannel lockChannel;
    private FileLock lock;
    private String storeId;
    private boolean healthy = true;
    private boolean closed;

    public TaskInbox(Path directory, int capacity) {
        this.directory = directory.toAbsolutePath().normalize();
        this.capacity = capacity;
        try {
            if (Files.isSymbolicLink(this.directory)) throw new IOException("invalid directory");
            Files.createDirectories(this.directory);
            Path lockPath = this.directory.resolve(".inbox.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("invalid lock");
            lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            if (lock == null) throw new IOException("locked");
            Path identity = this.directory.resolve("store.id");
            if (Files.exists(identity)) {
                if (Files.isSymbolicLink(identity) || Files.size(identity) > 64) throw new IOException("invalid identity");
                storeId = Files.readString(identity).trim();
                if (!TaskCommand.uuid(storeId)) throw new IOException("invalid identity");
            } else {
                try (var files = Files.list(this.directory)) {
                    if (files.anyMatch(file -> file.getFileName().toString().endsWith(".task"))) throw new IOException("identity missing");
                }
                storeId = UUID.randomUUID().toString();
                atomicWrite(identity, (storeId + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            try (var files = Files.list(this.directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".task")).sorted().toList()) {
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > 65536) throw new IOException("invalid record");
                    Entry entry = decode(Files.readAllBytes(file));
                    if (!file.getFileName().toString().equals(entry.command().commandId() + ".task")
                            || !entry.task().storeId().equals(storeId) || records.putIfAbsent(entry.command().commandId(), entry) != null)
                        throw new IOException("invalid record identity");
                }
            }
        } catch (Exception failure) {
            healthy = false;
            closeResources();
            throw new TaskRejected(503, "inbox_unavailable");
        }
    }

    public synchronized String storeId() { return storeId; }
    public synchronized boolean healthy() { return healthy && !closed; }
    public synchronized Entry get(String id) { return records.get(id); }
    public synchronized List<Entry> all() { return List.copyOf(records.values()); }
    public synchronized boolean full() { return records.size() >= capacity; }

    public synchronized void put(Entry entry) {
        if (!healthy()) throw new TaskRejected(503, "inbox_unavailable");
        if (!records.containsKey(entry.command().commandId()) && full()) throw new TaskRejected(503, "inbox_capacity");
        try {
            atomicWrite(directory.resolve(entry.command().commandId() + ".task"), encode(entry));
            records.put(entry.command().commandId(), entry);
        } catch (Exception failure) {
            healthy = false;
            throw new TaskRejected(503, "inbox_unavailable");
        }
    }

    private void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temp = directory.resolve(".write-" + UUID.randomUUID() + ".tmp");
        try {
            try (FileChannel file = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            // 不支持原子替换就失败，禁止退化为可能撕裂旧记录的复制/直接覆盖。
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel dir = FileChannel.open(directory, StandardOpenOption.READ)) { dir.force(true); }
            catch (IOException | UnsupportedOperationException unsupported) {
                // Windows 的 JDK 通常不能打开目录；保证边界见文档，不承诺跨平台断电恢复。
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
                    throw new IOException("directory sync failed");
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private byte[] encode(Entry entry) throws Exception {
        TaskView task = entry.task();
        byte[] payload = Json.object("command", entry.command().json(), "target", entry.targetContainer(),
                "status", task.status(), "message", task.message(), "createdAt", task.createdAt(),
                "startedAt", task.startedAt(), "finishedAt", task.finishedAt(),
                "resultCode", task.resultCode(), "observedStatus", task.observedStatus()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (payload.length > 64000) throw new IOException("record too large");
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
        return ByteBuffer.allocate(8 + payload.length + digest.length).putInt(MAGIC).putInt(payload.length).put(payload).put(digest).array();
    }

    private Entry decode(byte[] bytes) throws Exception {
        if (bytes.length < 40) throw new IOException("invalid record");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        if (buffer.getInt() != MAGIC) throw new IOException("invalid format");
        int length = buffer.getInt();
        if (length < 0 || length != bytes.length - 40) throw new IOException("invalid length");
        byte[] payload = new byte[length]; buffer.get(payload);
        byte[] digest = new byte[32]; buffer.get(digest);
        if (!MessageDigest.isEqual(digest, MessageDigest.getInstance("SHA-256").digest(payload))) throw new IOException("checksum mismatch");
        Map<String, String> values = Json.flatObject(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
        if (!values.keySet().equals(Set.of("command", "target", "status", "message", "createdAt", "startedAt", "finishedAt", "resultCode", "observedStatus")))
            throw new IOException("invalid fields");
        TaskCommand command = TaskCommand.parse(values.get("command"));
        if (!Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN").contains(values.get("status"))
                || values.get("target") == null || !values.get("target").matches("[A-Za-z0-9_.-]{1,64}")
                || values.get("resultCode") == null || values.get("message") == null) throw new IOException("invalid state");
        Instant.parse(values.get("createdAt"));
        for (String field : List.of("startedAt", "finishedAt")) if (values.get(field) != null) Instant.parse(values.get(field));
        TaskView task = new TaskView(command.commandId(), command.instanceId(), command.action(), values.get("status"),
                values.get("message"), values.get("createdAt"), values.get("startedAt"), values.get("finishedAt"),
                command.expectedExecutionMode(), command.expectedNodeId(), command.expectedStoreId(), values.get("resultCode"), values.get("observedStatus"));
        return new Entry(command, values.get("target"), task);
    }

    @Override public synchronized void close() { closed = true; closeResources(); }
    private void closeResources() {
        try { if (lock != null && lock.isValid()) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
}
