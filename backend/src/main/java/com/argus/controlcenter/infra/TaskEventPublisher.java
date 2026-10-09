package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import java.time.Instant;

/** 任务事件的异步通知边界；事件事实已先写入 MySQL。 */
public interface TaskEventPublisher {
    void publish(String taskId,long sequence,TaskStatus from,TaskStatus to,String actor,String reason,Instant occurredAt);
}
