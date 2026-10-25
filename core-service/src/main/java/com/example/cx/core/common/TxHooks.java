package com.example.cx.core.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Small helper: run something only after the surrounding transaction COMMITS (e.g. cache eviction). */
public final class TxHooks {
    private TxHooks() {}

    public static void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { r.run(); }
            });
        } else {
            r.run();
        }
    }
}
