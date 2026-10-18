package com.quantgen.payment.service;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quantgen.common.config.TimeConfig;

/** F-PAY-9: runs the day's scheduled transfers at 07:00 IST. Safe on several instances (rows are SKIP LOCKED). */
@Component
class ScheduledTransferJob {

    private static final Logger log = LoggerFactory.getLogger(ScheduledTransferJob.class);

    private final ScheduledTransferService scheduled;
    private final Clock clock;

    ScheduledTransferJob(ScheduledTransferService scheduled, Clock clock) {
        this.scheduled = scheduled;
        this.clock = clock;
    }

    @Scheduled(cron = "${qg.payments.scheduled-cron:0 0 7 * * *}", zone = "Asia/Kolkata")
    void run() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        try {
            int attempted = scheduled.runDue(today);
            if (attempted > 0) {
                log.info("Ran {} scheduled transfer(s) for {}", attempted, today);
            }
        } catch (RuntimeException e) {
            log.error("Scheduled transfer run for {} failed", today, e);
        }
    }
}
