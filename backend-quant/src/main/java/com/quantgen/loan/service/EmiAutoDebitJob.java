package com.quantgen.loan.service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;

/**
 * F-LOAN-6, daily at 06:00 IST (ARCHITECTURE §7): auto-debits every unpaid instalment due today or
 * earlier, oldest first, each in its own transaction. When one can't be paid (low balance, frozen or
 * closed account), it and the loan's other due instalments become OVERDUE and the customer is notified.
 * Also sends EMI_DUE reminders three days ahead. An advisory lock held for the whole run stops two
 * instances (or an overlapping manual run) from processing at the same time.
 */
@Component
public class EmiAutoDebitJob {

    static final int REMINDER_DAYS_AHEAD = 3;
    private static final Logger log = LoggerFactory.getLogger(EmiAutoDebitJob.class);

    private final LoanService loans;
    private final AdvisoryLocks locks;
    private final TransactionTemplate tx;
    private final Clock clock;

    public EmiAutoDebitJob(LoanService loans, AdvisoryLocks locks, PlatformTransactionManager txManager, Clock clock) {
        this.loans = loans;
        this.locks = locks;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
    }

    public record Result(int paid, int overdue, int reminders, int failedLoans) {
        static final Result SKIPPED = new Result(0, 0, 0, 0);
    }

    @Scheduled(cron = "${qg.loans.auto-debit-cron:0 0 6 * * *}", zone = "Asia/Kolkata")
    void scheduledRun() {
        Result result = runFor(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
        log.info("EMI auto-debit: paid={} overdue={} reminders={} failedLoans={}", result.paid(), result.overdue(),
                result.reminders(), result.failedLoans());
    }

    /** Processes dues for the given IST business day. Returns {@link Result#SKIPPED} if another run holds the lock. */
    public Result runFor(LocalDate today) {
        Result result = tx.execute(status -> {
            if (!locks.tryLockForTransaction("emi-auto-debit")) {
                return Result.SKIPPED;
            }
            int paid = 0;
            int overdue = 0;
            int failed = 0;
            for (UUID loanId : loans.loansWithDues(today)) {
                try {
                    while (loans.autoDebitNext(loanId, today)) {
                        paid++;
                    }
                } catch (ApiException e) {
                    // Expected: INSUFFICIENT_FUNDS, ACCOUNT_FROZEN, INVALID_STATE (closed account).
                    overdue += loans.markOverdue(loanId, today);
                } catch (RuntimeException e) {
                    failed++;
                    log.error("EMI auto-debit failed for loan {}", loanId, e);
                }
            }
            int reminders = loans.sendReminders(today.plusDays(REMINDER_DAYS_AHEAD));
            return new Result(paid, overdue, reminders, failed);
        });
        return result == null ? Result.SKIPPED : result;
    }
}
