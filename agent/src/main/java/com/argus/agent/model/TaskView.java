package com.argus.agent.model;

/** Agent 持久化任务的对外只读视图；UNKNOWN 不能自动重放。 */
public record TaskView(String taskId, String instanceId, String action, String status,
                       String message, String createdAt, String startedAt, String finishedAt,
                       String executionMode, String nodeId, String storeId, String resultCode, String observedStatus) { }
