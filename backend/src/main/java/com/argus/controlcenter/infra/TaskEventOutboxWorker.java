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
        Instant now = Instant.now();
        for (TaskEventOutboxRepository.Event event : outbox.claim(20, now, now.plusSeconds(30))) {
            try {
                publisher.publish(event.taskId(), event.sequence(), event.from(), event.to(), event.actor(), event.reason(), event.occurredAt());
                outbox.markPublished(event.id(), Instant.now());
            } catch (RuntimeException failure) {
                int attempts = event.attempts() + 1;
                long delay = Math.min(300, 1L << Math.min(attempts, 8));
                outbox.markRetry(event.id(), attempts, Instant.now().plus(Duration.ofSeconds(delay)), failure.toString());
            }
        }
    }
}
