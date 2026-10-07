package com.argus.agent.model;

/** Agent 内存任务的对外只读视图。 */
public record TaskView(String taskId, String instanceId, String action, String status,
                       String message, String createdAt, String finishedAt) { }
