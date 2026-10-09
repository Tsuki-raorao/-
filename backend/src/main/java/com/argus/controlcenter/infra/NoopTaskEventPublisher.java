package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "argus.messaging", name = "enabled", havingValue = "false", matchIfMissing = true)
public class NoopTaskEventPublisher implements TaskEventPublisher {
    @Override public void publish(String taskId,long sequence,TaskStatus from,TaskStatus to,String actor,String reason,Instant occurredAt) { }
}
