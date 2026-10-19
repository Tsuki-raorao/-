package com.argus.controlcenter.infra;

import com.argus.controlcenter.domain.TaskStatus;
import db.migration.V8__task_event_outbox;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TaskEventOutboxTest {
    JdbcTemplate jdbc;
    TaskEventOutboxRepository outbox;
    Instant now = Instant.parse("2026-10-19T00:00:00Z");

    @BeforeEach void prepare() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE tasks(id VARCHAR(64) PRIMARY KEY)");
        jdbc.update("INSERT INTO tasks VALUES('task-1')");
        try (var connection = source.getConnection()) {
            var context = mock(Context.class);
            when(context.getConnection()).thenReturn(connection);
            new V8__task_event_outbox().migrate(context);
        }
        outbox = new TaskEventOutboxRepository(jdbc);
    }

    void append() { outbox.append("task-1",1,null,TaskStatus.PENDING,"operator","ACCEPTED",now); }
    String status() { return jdbc.queryForObject("SELECT status FROM task_event_outbox",String.class); }

    @Test void expiredProcessingCanBeRecoveredAndOldOwnerCannotComplete() {
        append();
        var old = outbox.claim(1,now,now.plusSeconds(30)).get(0);
        assertThat(outbox.claim(1,now.plusSeconds(1),now.plusSeconds(31))).isEmpty();
        var renewed = outbox.claim(1,now.plusSeconds(31),now.plusSeconds(61)).get(0);
        assertThat(renewed.attempts()).isEqualTo(2);
        outbox.markPublished(old,now.plusSeconds(32));
        outbox.markRetry(old,now.plusSeconds(33),"late");
        assertThat(status()).isEqualTo("PROCESSING");
        outbox.markPublished(renewed,now.plusSeconds(33));
        assertThat(status()).isEqualTo("PUBLISHED");
        assertThat(outbox.claim(1,now.plusSeconds(100),now.plusSeconds(130))).isEmpty();
    }

    @Test void retryWaitsUntilDueAndExpiredOwnerCannotPublish() {
        append();
        var event = outbox.claim(1,now,now.plusSeconds(30)).get(0);
        outbox.markPublished(event,now.plusSeconds(31));
        assertThat(status()).isEqualTo("PROCESSING");
        outbox.markRetry(event,now.plusSeconds(40),"offline");
        assertThat(outbox.claim(1,now.plusSeconds(39),now.plusSeconds(69))).isEmpty();
        assertThat(outbox.claim(1,now.plusSeconds(40),now.plusSeconds(70))).hasSize(1);
    }

    @Test void transactionRollbackDoesNotLeaveAnEvent() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        tx.executeWithoutResult(s -> { append(); s.setRollbackOnly(); });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_event_outbox",Integer.class)).isZero();
    }

    @Test void publisherFailureRetainsEventForRetry() {
        append();
        var publisher = mock(TaskEventPublisher.class);
        doThrow(new IllegalStateException("offline")).when(publisher).publish(anyString(),anyLong(),any(),any(),anyString(),anyString(),any());
        new TaskEventOutboxWorker(outbox,publisher).publishDue();
        assertThat(status()).isEqualTo("RETRY");
        assertThat(jdbc.queryForObject("SELECT attempts FROM task_event_outbox",Integer.class)).isEqualTo(1);
    }

    @Test void concurrentWorkersOnlyOneClaimsTheEvent() throws Exception {
        append();
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            java.util.concurrent.Callable<Integer> claim=() -> {
                start.await(); return outbox.claim(1,now,now.plusSeconds(30)).size();
            };
            var first=workers.submit(claim); var second=workers.submit(claim); start.countDown();
            assertThat(first.get(5,java.util.concurrent.TimeUnit.SECONDS)+second.get(5,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }
}
