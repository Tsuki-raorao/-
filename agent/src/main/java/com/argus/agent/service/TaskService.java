package com.argus.agent.service;

import com.argus.agent.model.TaskView;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class TaskService implements AutoCloseable {
    private final InstanceService instances;
    private final Map<String, TaskView> tasks = new ConcurrentHashMap<>();
    private final ExecutorService executor;
    public TaskService(InstanceService instances) { this(instances, 4); }
    public TaskService(InstanceService instances, int maxConcurrentTasks) {
        this.instances = instances;
        // 有界队列让突发任务产生可观察的 429，而不是无限创建线程。
        int queueCapacity = Math.max(4, maxConcurrentTasks * 4);
        this.executor = new ThreadPoolExecutor(maxConcurrentTasks, maxConcurrentTasks, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), new ThreadPoolExecutor.AbortPolicy());
    }
    public TaskView submit(String instanceId, String action) {
        if (instanceId == null || action == null || !(action.equals("start") || action.equals("stop") || action.equals("restart")))
            throw new IllegalArgumentException("instanceId and action(start|stop|restart) are required");
        String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
        TaskView task = new TaskView(id, instanceId, action, "PENDING", "queued", now, null); tasks.put(id, task);
        try { executor.submit(() -> run(task)); return task; }
        catch (RejectedExecutionException e) { tasks.remove(id); throw e; }
    }
    public TaskView get(String id) { return tasks.get(id); }
    private void run(TaskView initial) {
        tasks.put(initial.taskId(), new TaskView(initial.taskId(), initial.instanceId(), initial.action(), "RUNNING", "executing", initial.createdAt(), null));
        try {
            String message = instances.execute(initial.instanceId(), initial.action());
            tasks.put(initial.taskId(), new TaskView(initial.taskId(), initial.instanceId(), initial.action(), "SUCCEEDED", message, initial.createdAt(), Instant.now().toString()));
        } catch (Exception e) {
            tasks.put(initial.taskId(), new TaskView(initial.taskId(), initial.instanceId(), initial.action(), "FAILED", e.getMessage(), initial.createdAt(), Instant.now().toString()));
        }
    }
    @Override public void close() { executor.shutdownNow(); }
}
