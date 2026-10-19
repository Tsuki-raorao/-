package com.argus.controlcenter.infra;

import java.time.Duration;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅在启用 RabbitMQ 时发送 Outbox；数据库事实保留，失败采用退避重试。 */
@Component
@ConditionalOnProperty(prefix = "argus.messaging", name = "enabled", havingValue = "true")
public class TaskEventOutboxWorker {
    private final TaskEventOutboxRepository outbox;
    private final TaskEventPublisher publisher;
    public TaskEventOutboxWorker(TaskEventOutboxRepository outbox, TaskEventPublisher publisher) {
        this.outbox = outbox; this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${ARGUS_MQ_OUTBOX_POLL_MS:1000}")
    public void publishDue() {
        for (int count=0; count<20; count++) {
            Instant now = Instant.now();
            var claimed = outbox.claim(1, now, now.plusSeconds(30));
            if (claimed.isEmpty()) break;
            TaskEventOutboxRepository.Event event = claimed.get(0);
            try {
                publisher.publish(event.taskId(), event.sequence(), event.from(), event.to(), event.actor(), event.reason(), event.occurredAt());
                outbox.markPublished(event, Instant.now());
            } catch (RuntimeException failure) {
                long delay = Math.min(300, 1L << Math.min(event.attempts(), 9));
                outbox.markRetry(event, Instant.now().plus(Duration.ofSeconds(delay)), failure.getClass().getSimpleName());
            }
        }
    }
}
