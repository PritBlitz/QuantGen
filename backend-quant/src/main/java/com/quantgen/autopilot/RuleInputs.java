package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.quantgen.analytics.AnalyticsRange;
import com.quantgen.autopilot.RuleDsl.Trigger;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.insights.InsightsService;
import com.quantgen.payment.service.BillService;

/**
 * Inputs the engine and the replay must compute the same way: the round-ups swept by a ROUND_UP_SWEEP action, the
 * bill a PAY_BILL action pays, and how much of a budget a BUDGET_THRESHOLD trigger has used. Each depends only on
 * stored data and a date, so a resumed run or a replay gets exactly the same number.
 */
@Component
class RuleInputs {

    /** Spending in a category this month against its budget; {@code pct} is whole percent, rounded down. */
    record BudgetUse(BigDecimal spent, BigDecimal limit, int pct) {
    }

    private final JdbcTemplate jdbc;
    private final InsightsService insights;
    private final BillService bills;

    RuleInputs(JdbcTemplate jdbc, InsightsService insights, BillService bills) {
        this.jdbc = jdbc;
        this.insights = insights;
        this.bills = bills;
    }

    /** The bill a PAY_BILL action pays on {@code day}: zero if that cycle is paid or the biller was removed. */
    BigDecimal billDue(UUID savedBillerId, LocalDate day) {
        return jdbc.query("SELECT user_id, biller_id, consumer_number FROM saved_billers WHERE id = ?", (rs, i) -> {
            UUID user = rs.getObject(1, UUID.class);
            UUID biller = rs.getObject(2, UUID.class);
            String consumer = rs.getString(3);
            return bills.paidFor(user, biller, consumer, day) ? BigDecimal.ZERO
                    : BillService.amountDue(biller, consumer, day).setScale(2, RoundingMode.UNNECESSARY);
        }, savedBillerId).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /** The scheduled day before {@code due} for this SCHEDULE trigger (a month or a week earlier). */
    static LocalDate previousDue(Trigger t, LocalDate due) {
        return "MONTHLY".equals(t.frequency()) ? due.minusMonths(1) : due.minusDays(7);
    }

    /**
     * Round-ups to the next {@code roundTo} on the account's spends from the previous scheduled day up to (not
     * including) {@code due}, IST. Spends are what F-SAV-2 rounds up: payments to another person, bill payments and
     * card payments. Autopilot's own transfers don't count, so rules can't feed each other.
     */
    BigDecimal roundUps(UUID accountId, int roundTo, Trigger schedule, LocalDate due) {
        BigDecimal sum = jdbc.queryForObject("""
                SELECT COALESCE(sum(ceil(l.amount / ?) * ? - l.amount), 0) FROM ledger_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                 WHERE l.ledger_account_id = ? AND l.direction = 'DEBIT' AND l.created_at >= ? AND l.created_at < ?
                   AND (e.type IN ('BILL_PAYMENT', 'CARD')
                        OR (e.type = 'TRANSFER'
                            AND EXISTS (SELECT 1 FROM ledger_lines ol JOIN accounts oa ON oa.id = ol.ledger_account_id
                                         JOIN accounts src ON src.id = l.ledger_account_id
                                        WHERE ol.journal_entry_id = l.journal_entry_id AND oa.user_id <> src.user_id)
                            AND NOT EXISTS (SELECT 1 FROM transfers x WHERE x.journal_entry_id = l.journal_entry_id
                                             AND x.source = 'AUTOPILOT')))
                """, BigDecimal.class, roundTo, roundTo, accountId, start(previousDue(schedule, due)), start(due));
        return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_EVEN);
    }

    /** Budget use for the month of {@code asOf}, counting spends up to the end of that day; null without a budget. */
    BudgetUse budgetUse(UUID userId, String category, LocalDate asOf) {
        List<BigDecimal> limit = jdbc.queryForList("SELECT monthly_limit FROM budgets WHERE user_id = ? AND category = ?",
                BigDecimal.class, userId, category);
        if (limit.isEmpty()) {
            return null;
        }
        BigDecimal spent = insights.spending(userId, new AnalyticsRange(YearMonth.from(asOf).atDay(1), asOf),
                        InsightsService.GroupBy.category).stream()
                .filter(r -> r.key().equals(category)).map(InsightsService.SpendingRow::amount).findFirst()
                .orElse(BigDecimal.ZERO).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_EVEN);
        int pct = spent.multiply(BigDecimal.valueOf(100)).divide(limit.getFirst(), 0, RoundingMode.DOWN).intValue();
        return new BudgetUse(spent, limit.getFirst(), pct);
    }

    private static Timestamp start(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
    }
}
