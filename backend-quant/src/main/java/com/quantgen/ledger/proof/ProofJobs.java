package com.quantgen.ledger.proof;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.ledger.proof.ChainVerifier.ChainReport;

/**
 * The daily snapshot (F-POB-2) and the nightly chain check (F-POB-4). The snapshot job runs every minute and takes
 * today's (IST) snapshot when there isn't one yet, so midnight is covered within a minute and a server that was
 * down at midnight, or a fresh database, catches up on its own.
 */
@Component
public class ProofJobs {

    private static final Logger log = LoggerFactory.getLogger(ProofJobs.class);

    private final BalanceSnapshotService snapshots;
    private final LedgerSealer sealer;
    private final ChainVerifier verifier;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;

    ProofJobs(BalanceSnapshotService snapshots, LedgerSealer sealer, ChainVerifier verifier, AuditService audit,
              NotificationService notifications, JdbcTemplate jdbc, Clock clock, PlatformTransactionManager txManager) {
        this.snapshots = snapshots;
        this.sealer = sealer;
        this.verifier = verifier;
        this.audit = audit;
        this.notifications = notifications;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(initialDelayString = "${qg.proofs.snapshot-initial-delay-millis:20000}",
            fixedDelayString = "${qg.proofs.snapshot-check-millis:60000}")
    void snapshotIfDue() {
        try {
            LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
            if (!snapshots.exists(today)) {
                sealer.sealPending(true);
                snapshots.take(today);
            }
        } catch (RuntimeException e) {
            log.error("Balance snapshot failed", e);
        }
    }

    @Scheduled(cron = "${qg.proofs.verify-cron:0 30 0 * * *}", zone = "Asia/Kolkata")
    void nightlyVerify() {
        try {
            // From the last checkpoint on weekdays; the whole chain from genesis every Sunday.
            boolean sunday = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).getDayOfWeek() == DayOfWeek.SUNDAY;
            verifyAndRecord(null, !sunday);
        } catch (RuntimeException e) {
            log.error("Nightly ledger chain verification failed to run", e);
        }
    }

    /**
     * Verifies the chain and audits the result ({@code actorId} null for the nightly job). A break also notifies
     * every admin.
     */
    public ChainReport verifyAndRecord(UUID actorId, boolean fromCheckpoint) {
        ChainReport report = verifier.verify(fromCheckpoint);
        String details = "intact=" + report.intact() + ", from=" + report.fromPosition() + ", checkedUpTo=" + report.checkedUpTo()
                + (report.intact() ? "" : ", firstBrokenPosition=" + report.firstBrokenPosition());
        tx.executeWithoutResult(status -> {
            audit.record(actorId, report.intact() ? "LEDGER_CHAIN_VERIFIED" : "LEDGER_CHAIN_BROKEN", "LEDGER",
                    report.intact() ? null : String.valueOf(report.firstBrokenPosition()), details);
            if (!report.intact()) {
                List<UUID> admins = jdbc.queryForList("SELECT id FROM users WHERE role = 'ADMIN'", UUID.class);
                for (UUID admin : admins) {
                    notifications.notify(admin, NotificationType.LEDGER_INTEGRITY, "Ledger integrity alert",
                            "The ledger seal chain no longer matches the ledger from position "
                                    + report.firstBrokenPosition() + ". Investigate before the next snapshot.");
                }
            }
        });
        return report;
    }
}
