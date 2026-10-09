package com.argus.controlcenter.infra;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AfterCommitNotificationTest {
    private TransactionTemplate transaction() {
        return new TransactionTemplate(new DataSourceTransactionManager(
                new DriverManagerDataSource("jdbc:h2:mem:notification", "sa", "")));
    }

    @Test void sendsOnlyAfterCommit() {
        AtomicInteger sent = new AtomicInteger();
        transaction().executeWithoutResult(status -> {
            AfterCommitNotification.register(sent::incrementAndGet);
            assertThat(sent.get()).isZero();
        });
        assertThat(sent.get()).isEqualTo(1);
    }

    @Test void rollbackNeverSends() {
        AtomicInteger sent = new AtomicInteger();
        transaction().executeWithoutResult(status -> {
            AfterCommitNotification.register(sent::incrementAndGet);
            status.setRollbackOnly();
        });
        assertThat(sent.get()).isZero();
    }

    @Test void failedNotificationDoesNotTurnCommittedOperationIntoFailure() {
        AtomicInteger sent = new AtomicInteger();
        assertThatCode(() -> transaction().executeWithoutResult(status -> {
            AfterCommitNotification.register(() -> { throw new IllegalStateException("offline"); });
            AfterCommitNotification.register(sent::incrementAndGet);
        })).doesNotThrowAnyException();
        assertThat(sent.get()).isEqualTo(1);
    }

    @Test void missingTransactionIsRejected() {
        assertThatThrownBy(() -> AfterCommitNotification.register(() -> { }))
                .isInstanceOf(IllegalStateException.class);
    }
}
