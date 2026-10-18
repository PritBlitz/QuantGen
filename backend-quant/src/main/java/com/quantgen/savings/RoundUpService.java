package com.quantgen.savings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.events.EventConsumer;
import com.quantgen.common.events.OutboxEvent;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;

import tools.jackson.databind.JsonNode;

/**
 * F-SAV-2 round-ups. Every payment to someone else or bill paid from an account with round-ups on is rounded up to
 * the next ₹10/₹50/₹100, and the difference is recorded as an accrual (an outbox consumer, so it never slows the
 * payment down and runs once per ledger entry). Once a day the accruals are swept into the goal as one summed
 * transfer per (account, goal), to keep the ledger quiet. A sweep that can't post (not enough money, an account
 * frozen or closed) marks its accruals SKIPPED: round-ups are best effort and never overdraw anything.
 */
@Service
public class RoundUpService implements EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(RoundUpService.class);

    public record RoundUpDto(UUID accountId, boolean enabled, int roundTo, UUID goalAccountId) {
    }

    private record Pending(UUID sourceAccountId, UUID goalAccountId, UUID userId) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final TransactionTemplate tx;

    public RoundUpService(JdbcTemplate jdbc, AccountService accounts, LedgerService ledger,
                          PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public RoundUpDto get(UUID userId, UUID accountId) {
        everyday(userId, accountId);
        return jdbc.query("SELECT enabled, round_to, goal_account_id FROM round_up_settings WHERE account_id = ?",
                (rs, i) -> new RoundUpDto(accountId, rs.getBoolean("enabled"), rs.getInt("round_to"),
                        rs.getObject("goal_account_id", UUID.class)), accountId).stream().findFirst()
                .orElse(new RoundUpDto(accountId, false, 10, null));
    }

    @Transactional
    public RoundUpDto set(UUID userId, UUID accountId, boolean enabled, int roundTo, UUID goalAccountId) {
        everyday(userId, accountId);
        if (roundTo != 10 && roundTo != 50 && roundTo != 100) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "roundTo", "must be 10, 50 or 100");
        }
        UUID goal = null;
        if (goalAccountId != null) {
            AccountRef g = accounts.findById(goalAccountId).filter(a -> a.userId().equals(userId)
                    && a.type() == AccountType.GOAL && a.status() != AccountStatus.CLOSED)
                    .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "goalAccountId",
                            "pick one of your goals"));
            goal = g.id();
        }
        if (enabled && goal == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "goalAccountId", "pick one of your goals");
        }
        jdbc.update("""
                INSERT INTO round_up_settings (account_id, enabled, round_to, goal_account_id) VALUES (?, ?, ?, ?)
                ON CONFLICT (account_id) DO UPDATE SET enabled = EXCLUDED.enabled, round_to = EXCLUDED.round_to,
                    goal_account_id = EXCLUDED.goal_account_id, updated_at = now()
                """, accountId, enabled, roundTo, goal);
        return get(userId, accountId);
    }

    private void everyday(UUID userId, UUID accountId) {
        AccountRef account = accounts.requireOwned(userId, accountId);
        if (!account.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountId",
                    "round-ups work on savings and checking accounts");
        }
    }

    // ------------------------------------------------------------------ accrual (outbox consumer)

    @Override
    public String name() {
        return "round-ups";
    }

    @Override
    public boolean accepts(String eventType) {
        return OutboxEvent.MONEY_MOVED.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        JsonNode p = event.payload();
        String type = p.path("type").asString("");
        if (!JournalType.TRANSFER.name().equals(type) && !JournalType.BILL_PAYMENT.name().equals(type)) {
            return;
        }
        UUID entryId = UUID.fromString(p.path("entryId").asString());
        for (JsonNode line : p.path("lines")) {
            if (!"DEBIT".equals(line.path("direction").asString())) {
                continue;
            }
            UUID accountId = UUID.fromString(line.path("ledgerAccountId").asString());
            List<int[]> roundTo = jdbc.query("SELECT round_to FROM round_up_settings WHERE account_id = ? AND enabled",
                    (rs, i) -> new int[]{rs.getInt(1)}, accountId);
            if (roundTo.isEmpty()) {
                continue;
            }
            // Only spends count: a transfer must go to another user (never a move between own accounts or a sweep).
            if (JournalType.TRANSFER.name().equals(type) && !Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM ledger_lines ol JOIN accounts oa ON oa.id = ol.ledger_account_id
                                    JOIN accounts src ON src.id = ?
                                   WHERE ol.journal_entry_id = ? AND oa.user_id <> src.user_id)
                    """, Boolean.class, accountId, entryId))) {
                continue;
            }
            BigDecimal spend = new BigDecimal(line.path("amount").asString());
            BigDecimal amount = roundUp(spend, roundTo.getFirst()[0]);
            if (amount.signum() == 0) {
                continue;
            }
            LocalDate day = event.createdAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
            jdbc.update("""
                    INSERT INTO round_up_accruals (source_account_id, goal_account_id, journal_entry_id, spend_amount,
                                                   amount, day)
                    SELECT ?, goal_account_id, ?, ?, ?, ? FROM round_up_settings WHERE account_id = ? AND enabled
                    ON CONFLICT (journal_entry_id, source_account_id) DO NOTHING
                    """, accountId, entryId, spend, amount, Date.valueOf(day), accountId);
        }
    }

    /** The difference up to the next multiple of {@code roundTo}; zero for an exact multiple. */
    static BigDecimal roundUp(BigDecimal spend, int roundTo) {
        BigDecimal step = BigDecimal.valueOf(roundTo);
        BigDecimal next = spend.divide(step, 0, RoundingMode.CEILING).multiply(step);
        return next.subtract(spend).setScale(2, RoundingMode.UNNECESSARY);
    }

    // ------------------------------------------------------------------ daily sweep

    /** Sweeps every pending accrual dated on or before {@code day}. Returns how many sweeps posted. */
    public int sweep(LocalDate day) {
        List<Pending> groups = jdbc.query("""
                SELECT DISTINCT r.source_account_id, r.goal_account_id, a.user_id FROM round_up_accruals r
                  JOIN accounts a ON a.id = r.source_account_id
                 WHERE r.status = 'PENDING' AND r.day <= ?
                """, (rs, i) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class)), Date.valueOf(day));
        int posted = 0;
        for (Pending g : groups) {
            try {
                Boolean ok = tx.execute(status -> sweepOne(g, day));
                if (Boolean.TRUE.equals(ok)) {
                    posted++;
                }
            } catch (ApiException refused) {
                // Not enough money or an account can't post: drop these round-ups rather than retry forever.
                tx.executeWithoutResult(status -> jdbc.update("UPDATE round_up_accruals SET status = 'SKIPPED' "
                        + "WHERE status = 'PENDING' AND day <= ? AND source_account_id = ? AND goal_account_id = ?",
                        Date.valueOf(day), g.sourceAccountId(), g.goalAccountId()));
                log.info("Round-up sweep {} -> {} skipped: {}", g.sourceAccountId(), g.goalAccountId(),
                        refused.code());
            } catch (RuntimeException e) {
                log.error("Round-up sweep {} -> {} failed", g.sourceAccountId(), g.goalAccountId(), e);
            }
        }
        return posted;
    }

    private boolean sweepOne(Pending g, LocalDate day) {
        List<UUID> ids = jdbc.queryForList("""
                SELECT id FROM round_up_accruals WHERE status = 'PENDING' AND day <= ? AND source_account_id = ?
                   AND goal_account_id = ? FOR UPDATE SKIP LOCKED
                """, UUID.class, Date.valueOf(day), g.sourceAccountId(), g.goalAccountId());
        if (ids.isEmpty()) {
            return false;
        }
        BigDecimal total = jdbc.queryForObject("SELECT SUM(amount) FROM round_up_accruals WHERE id = ANY (?)",
                BigDecimal.class, (Object) ids.toArray(new UUID[0]));
        AccountRef source = accounts.findById(g.sourceAccountId()).orElseThrow();
        AccountRef goal = accounts.findById(g.goalAccountId()).filter(a -> a.userId().equals(g.userId())
                && a.type() == AccountType.GOAL).orElseThrow(() -> new ApiException(
                com.quantgen.common.error.ErrorCode.INVALID_STATE, "The goal no longer exists"));
        String goalName = jdbc.queryForObject("SELECT name FROM goals WHERE account_id = ?", String.class, goal.id());
        UUID entry = ledger.post(JournalRequest.builder(JournalType.TRANSFER, "Round-up savings", g.userId())
                .debit(source.id(), total, "To goal " + goalName)
                .credit(goal.id(), total, "Round-ups from " + AccountNumbers.mask(source.accountNumber()))
                .build()).journalEntryId();
        jdbc.update("UPDATE round_up_accruals SET status = 'SWEPT', sweep_entry_id = ? WHERE id = ANY (?)", entry,
                (Object) ids.toArray(new UUID[0]));
        return true;
    }
}
