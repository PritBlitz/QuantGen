package com.quantgen.savings;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quantgen.common.config.TimeConfig;

/** Nightly savings jobs (IST): FD maturities, savings interest accrual, and the monthly interest credit. */
@Component
class SavingsJobs {

    private static final Logger log = LoggerFactory.getLogger(SavingsJobs.class);
    /** Days the accrual job looks back, so a few missed nights (downtime) are caught up. */
    static final int ACCRUAL_CATCH_UP_DAYS = 7;

    private final FixedDepositService deposits;
    private final SavingsInterestService interest;
    private final Clock clock;

    SavingsJobs(FixedDepositService deposits, SavingsInterestService interest, Clock clock) {
        this.deposits = deposits;
        this.interest = interest;
        this.clock = clock;
    }

    @Scheduled(cron = "${qg.savings.nightly-cron:0 10 0 * * *}", zone = "Asia/Kolkata")
    void nightly() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        try {
            int matured = deposits.matureDue(today);
            if (matured > 0) {
                log.info("Matured {} fixed deposit(s)", matured);
            }
        } catch (RuntimeException e) {
            log.error("Fixed deposit maturity job failed", e);
        }
        try {
            for (int back = ACCRUAL_CATCH_UP_DAYS; back >= 1; back--) {
                interest.accrue(today.minusDays(back));
            }
            if (today.getDayOfMonth() == 1) {
                int credited = interest.credit(today.minusDays(1));
                log.info("Credited savings interest to {} account(s)", credited);
            }
        } catch (RuntimeException e) {
            log.error("Savings interest job failed", e);
        }
    }
}
