package com.quantgen.savings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.money.Money;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.SystemAccount;

/**
 * F-SAV-4: 3% p.a. on the daily closing balance of SAVINGS accounts. Each day's interest is
 * {@code closing × 3 / 36500}, kept to 4 decimal places (half-even); once a month the uncredited days are summed,
 * rounded half-even to the paisa and credited from {@code INTEREST_EXPENSE} as one INTEREST entry. A sum that
 * rounds to zero is carried forward to the next month.
 */
@Service
public class SavingsInterestService {

    private static final Logger log = LoggerFactory.getLogger(SavingsInterestService.class);

    public static final BigDecimal ANNUAL_RATE = new BigDecimal("3.00");

    private record Closing(UUID accountId, BigDecimal balance) {
    }

    private final JdbcTemplate jdbc;
    private final LedgerService ledger;
    private final TransactionTemplate tx;

    public SavingsInterestService(JdbcTemplate jdbc, LedgerService ledger, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    static BigDecimal dailyInterest(BigDecimal closing) {
        return closing.multiply(ANNUAL_RATE).divide(BigDecimal.valueOf(36500), 4, RoundingMode.HALF_EVEN);
    }

    /**
     * Records the day's interest for every open SAVINGS account with a positive closing balance (IST end of day).
     * Safe to re-run: a day already recorded for an account is left alone. Returns how many rows were added.
     */
    public int accrue(LocalDate day) {
        Timestamp cutoff = Timestamp.from(day.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        // One statement, so the cached balance and the later lines come from the same snapshot.
        List<Closing> closings = jdbc.query("""
                SELECT a.id, la.balance - COALESCE((
                         SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                           FROM ledger_lines l WHERE l.ledger_account_id = a.id AND l.created_at >= ?), 0) AS closing
                  FROM accounts a JOIN ledger_accounts la ON la.id = a.id
                 WHERE a.type = 'SAVINGS' AND a.status <> 'CLOSED' AND a.created_at < ?
                """, (rs, i) -> new Closing(rs.getObject(1, UUID.class), rs.getBigDecimal(2)), cutoff, cutoff);
        int added = 0;
        for (Closing c : closings) {
            if (c.balance().signum() <= 0) {
                continue;
            }
            added += jdbc.update("""
                    INSERT INTO interest_accruals (account_id, day, closing_balance, annual_rate, amount)
                    VALUES (?, ?, ?, ?, ?) ON CONFLICT (account_id, day) DO NOTHING
                    """, c.accountId(), Date.valueOf(day), c.balance(), ANNUAL_RATE, dailyInterest(c.balance()));
        }
        return added;
    }

    /** Credits every account's uncredited interest for days up to {@code lastDay}; returns how many were credited. */
    public int credit(LocalDate lastDay) {
        List<UUID> due = jdbc.queryForList("SELECT DISTINCT account_id FROM interest_accruals "
                + "WHERE credit_entry_id IS NULL AND day <= ?", UUID.class, Date.valueOf(lastDay));
        int credited = 0;
        for (UUID accountId : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> creditOne(accountId, lastDay)))) {
                    credited++;
                }
            } catch (RuntimeException e) {
                // E.g. the account was closed: its interest stays uncredited and is retried next month.
                log.warn("Savings interest for an account could not be credited: {}", e.toString());
            }
        }
        return credited;
    }

    private boolean creditOne(UUID accountId, LocalDate lastDay) {
        // Lock this account's uncredited rows so two runs can't credit the same days.
        List<Date> days = jdbc.queryForList("SELECT day FROM interest_accruals WHERE account_id = ? "
                + "AND credit_entry_id IS NULL AND day <= ? FOR UPDATE", Date.class, accountId, Date.valueOf(lastDay));
        if (days.isEmpty()) {
            return false;
        }
        BigDecimal sum = jdbc.queryForObject("SELECT SUM(amount) FROM interest_accruals WHERE account_id = ? "
                + "AND credit_entry_id IS NULL AND day <= ?", BigDecimal.class, accountId, Date.valueOf(lastDay));
        BigDecimal amount = Money.roundToPaise(sum);
        if (amount.signum() <= 0) {
            return false; // carried forward
        }
        UUID userId = jdbc.queryForObject("SELECT user_id FROM accounts WHERE id = ?", UUID.class, accountId);
        UUID entry = ledger.post(JournalRequest.builder(JournalType.INTEREST, "Savings interest", userId)
                .debit(SystemAccount.INTEREST_EXPENSE.id(), amount, null)
                .credit(accountId, amount, "Interest at " + ANNUAL_RATE.toPlainString() + "% p.a.")
                .build()).journalEntryId();
        jdbc.update("UPDATE interest_accruals SET credit_entry_id = ? WHERE account_id = ? "
                + "AND credit_entry_id IS NULL AND day <= ?", entry, accountId, Date.valueOf(lastDay));
        return true;
    }
}
