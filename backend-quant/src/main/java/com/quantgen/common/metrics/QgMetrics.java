package com.quantgen.common.metrics;

import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Business metrics (ARCHITECTURE §12.14). Tags are low-cardinality enums only: never user ids,
 * account numbers or amounts. Counters for money events are bumped after commit, so a rolled-back
 * transfer or order is never counted.
 */
@Component
public class QgMetrics {

    private final MeterRegistry registry;

    public QgMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** {@code qg.ledger.post}: latency of {@code LedgerService.post}, tagged with the journal type and outcome. */
    public void ledgerPost(String journalType, boolean success, long nanos) {
        Timer.builder("qg.ledger.post")
                .description("Time spent in LedgerService.post")
                .tag("type", journalType)
                .tag("outcome", success ? "success" : "error")
                .publishPercentileHistogram()
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** {@code qg.transfers}: one per committed transfer, tagged internal (own accounts) or external. */
    public void transferCompleted(boolean internal) {
        afterCommit(() -> registry.counter("qg.transfers", "kind", internal ? "internal" : "external").increment());
    }

    /** {@code qg.orders}: one per committed order. */
    public void orderFilled(String side) {
        afterCommit(() -> registry.counter("qg.orders", "side", side, "status", "FILLED").increment());
    }

    /** {@code qg.auth.logins}: result is success, failed or locked. Called outside the login transaction. */
    public void login(String result) {
        registry.counter("qg.auth.logins", "result", result).increment();
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
