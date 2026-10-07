package com.argus.controlcenter.service;

import com.argus.controlcenter.domain.*;
import com.argus.controlcenter.exception.NotFoundException;
import com.argus.controlcenter.repository.TaskRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class TaskService {
    private final TaskRepository repository; private final InstanceService instances;
    public TaskService(TaskRepository repository, InstanceService instances) { this.repository=repository; this.instances=instances; }
    public List<Task> findAll() { return repository.findAll(); }
    public Task findById(String id) { return repository.findById(id).orElseThrow(() -> new NotFoundException("task not found: " + id)); }
    /**
     * 执行实例操作并记录任务结果。
     *
     * <p>任务写入、实例状态更新和最终任务状态写入位于同一事务中，避免进程
     * 在中途退出后出现“实例已更新但任务仍运行中”的半完成状态。</p>
     */
    @Transactional
    public Task execute(String instanceId, String action) {
        if (instanceId == null || instanceId.isBlank()) throw new IllegalArgumentException("instanceId must not be blank");
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        String normalizedAction = action.trim().toUpperCase(Locale.ROOT);
        Instant now=Instant.now(); Task task=new Task(UUID.randomUUID().toString(), instanceId, normalizedAction, TaskStatus.RUNNING, "executing", now, null); repository.save(task);
        try { instances.applyAction(instanceId, normalizedAction); task.setStatus(TaskStatus.SUCCEEDED); task.setMessage("action completed"); }
        catch (RuntimeException e) { task.setStatus(TaskStatus.FAILED); task.setMessage(e.getMessage()); }
        task.setFinishedAt(Instant.now()); return repository.save(task);
    }
}
