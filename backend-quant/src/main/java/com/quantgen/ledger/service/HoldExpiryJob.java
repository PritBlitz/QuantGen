package com.quantgen.ledger.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Releases holds past their expiry (ARCHITECTURE section 12.3). Safe to run on several instances: rows are SKIP LOCKED. */
@Component
class HoldExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final LedgerService ledger;
    private final Clock clock;
    private final TransactionTemplate tx;

    HoldExpiryJob(LedgerService ledger, Clock clock, PlatformTransactionManager txManager) {
        this.ledger = ledger;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${qg.holds.expiry-sweep-millis:60000}")
    void sweep() {
        try {
            Integer expired = tx.execute(status -> ledger.expireHolds(clock.instant()));
            if (expired != null && expired > 0) {
                log.info("Expired {} hold(s)", expired);
            }
        } catch (RuntimeException e) {
            log.error("Hold expiry sweep failed", e);
        }
    }
}
