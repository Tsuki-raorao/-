package com.argus.controlcenter.domain;

import java.time.Instant;

/** 同一任务的事件序号在数据库事务中递增，不允许回写旧事件。 */
public record TaskEvent(String id, String taskId, long sequence, TaskStatus fromStatus,
                        TaskStatus toStatus, String actor, String reason, Instant occurredAt) { }
