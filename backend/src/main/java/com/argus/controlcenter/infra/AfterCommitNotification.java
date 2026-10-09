package com.argus.controlcenter.infra;

import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Best-effort notifications only; durable delivery requires an outbox. */
public final class AfterCommitNotification {
    private AfterCommitNotification() { }

    public static void register(Runnable action) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("notification requires an active transaction");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                try { action.run(); }
                catch (RuntimeException failure) {
                    LoggerFactory.getLogger(AfterCommitNotification.class)
                            .warn("Post-commit notification failed; database transaction remains committed");
                }
            }
        });
    }
}
