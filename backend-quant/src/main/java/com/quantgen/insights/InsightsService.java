package com.quantgen.insights;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.api.TransactionDto;
import com.quantgen.account.service.AccountService;
import com.quantgen.analytics.AnalyticsRange;
import com.quantgen.analytics.LedgerKinds;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.events.EventConsumer;
import com.quantgen.common.events.OutboxEvent;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.ledger.domain.JournalType;

import tools.jackson.databind.JsonNode;

/**
 * F-INS-1/2: spending by category or month, recategorizing, and monthly budgets with alerts at 80% and 100%.
 * "Spending" is exactly the analytics definition ({@link LedgerKinds#SPENDING_KINDS}), so the insights page, the
 * analytics summary and the budgets always agree. Budget alerts are checked by an outbox consumer after every
 * spend, and again after a recategorization; each threshold is sent once per category per month.
 */
@Service
public class InsightsService implements EventConsumer {

    public enum GroupBy { category, month }

    public record SpendingRow(String key, BigDecimal amount, long count) {
    }

    public record BudgetDto(String category, BigDecimal monthlyLimit, BigDecimal spent, BigDecimal pct) {
    }

    public record BudgetItem(String category, BigDecimal monthlyLimit) {
    }

    static final BigDecimal MAX_BUDGET = new BigDecimal("100000000.00");

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final CategoryService categories;
    private final AccountService accounts;
    private final NotificationService notifications;
    private final Clock clock;

    public InsightsService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, CategoryService categories,
                           AccountService accounts, NotificationService notifications, Clock clock) {
        this.jdbc = jdbc;
        this.named = named;
        this.categories = categories;
        this.accounts = accounts;
        this.notifications = notifications;
        this.clock = clock;
    }

    private static MapSqlParameterSource params(UUID userId, AnalyticsRange r) {
        return new MapSqlParameterSource().addValue("uid", userId)
                .addValue("lo", Timestamp.from(r.lo())).addValue("hi", Timestamp.from(r.hi()));
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    // ------------------------------------------------------------------ spending

    @Transactional(readOnly = true)
    public List<SpendingRow> spending(UUID userId, AnalyticsRange r, GroupBy groupBy) {
        String key = groupBy == GroupBy.month ? "to_char(day, 'YYYY-MM')" : "category";
        String order = groupBy == GroupBy.month ? "k" : "amount DESC, k";
        return named.query("WITH " + LedgerKinds.cl(true) + ", " + CategoryService.catCte() + """
                        SELECT %s AS k, SUM(amt) AS amount, SUM(sgn) AS cnt FROM cat
                         WHERE kind IN (%s) GROUP BY 1 HAVING SUM(amt) <> 0 ORDER BY %s
                        """.formatted(key, LedgerKinds.SPENDING, order), params(userId, r),
                (rs, i) -> new SpendingRow(rs.getString("k"), rs.getBigDecimal("amount"), rs.getLong("cnt")));
    }

    /** This month's spending per category (only positive totals). */
    Map<String, BigDecimal> spentThisMonth(UUID userId) {
        YearMonth m = YearMonth.from(today());
        Map<String, BigDecimal> out = new HashMap<>();
        for (SpendingRow row : spending(userId, new AnalyticsRange(m.atDay(1), m.atEndOfMonth()), GroupBy.category)) {
            if (row.amount().signum() > 0) {
                out.put(row.key(), row.amount());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ recategorize

    @Transactional
    public TransactionDto recategorize(UUID userId, UUID lineId, String category, boolean applyToCounterparty) {
        CategoryService.requireKey("category", category);
        CategoryService.LineFacts facts = categories.lineFacts(userId, lineId)
                .orElseThrow(() -> ApiException.notFound("Transaction"));
        if (applyToCounterparty) {
            if (facts.otherAccount() == null || userId.equals(facts.otherUser())) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "applyToCounterparty",
                        "only payments to or from another person have a counterparty");
            }
            jdbc.update("""
                    INSERT INTO category_overrides (user_id, counterparty_account_id, category) VALUES (?, ?, ?)
                    ON CONFLICT (user_id, counterparty_account_id)
                    DO UPDATE SET category = EXCLUDED.category, updated_at = now()
                    """, userId, facts.otherAccount(), category);
            // The counterparty rule now decides; drop per-transaction picks that would hide it on this one.
            jdbc.update("DELETE FROM transaction_categories WHERE line_id = ?", lineId);
        } else {
            jdbc.update("""
                    INSERT INTO transaction_categories (line_id, user_id, category) VALUES (?, ?, ?)
                    ON CONFLICT (line_id) DO UPDATE SET category = EXCLUDED.category, updated_at = now()
                    """, lineId, userId, category);
        }
        checkBudgets(userId);
        return accounts.transaction(userId, lineId);
    }

    // ------------------------------------------------------------------ budgets

    @Transactional(readOnly = true)
    public List<BudgetDto> budgets(UUID userId) {
        Map<String, BigDecimal> spent = spentThisMonth(userId);
        return jdbc.query("""
                SELECT b.category, b.monthly_limit FROM budgets b JOIN categories c ON c.key = b.category
                 WHERE b.user_id = ? ORDER BY c.sort_order
                """, (rs, i) -> {
            String cat = rs.getString(1);
            BigDecimal limit = rs.getBigDecimal(2);
            BigDecimal s = Money.roundToPaise(spent.getOrDefault(cat, BigDecimal.ZERO));
            return new BudgetDto(cat, limit, s, pct(s, limit));
        }, userId);
    }

    static BigDecimal pct(BigDecimal spent, BigDecimal limit) {
        return spent.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_EVEN);
    }

    /** Replaces the user's whole set of budgets (an empty list removes them all). */
    @Transactional
    public List<BudgetDto> replaceBudgets(UUID userId, List<BudgetItem> items) {
        if (items.size() > CategoryService.KEYS.size()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "budgets", "too many budgets");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            BudgetItem item = items.get(i);
            String field = "[" + i + "]";
            if (item == null) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field, "must not be null");
            }
            CategoryService.requireKey(field + ".category", item.category());
            if ("INCOME".equals(item.category())) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field + ".category", "income can't have a budget");
            }
            if (!seen.add(item.category())) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field + ".category", "is listed twice");
            }
            BigDecimal limit = item.monthlyLimit();
            if (limit == null || limit.compareTo(new BigDecimal("1.00")) < 0 || limit.compareTo(MAX_BUDGET) > 0
                    || limit.stripTrailingZeros().scale() > 2) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field + ".monthlyLimit",
                        "must be between 1.00 and 100000000.00 with at most 2 decimals");
            }
        }
        jdbc.update("DELETE FROM budgets WHERE user_id = ?", userId);
        for (BudgetItem item : items) {
            jdbc.update("INSERT INTO budgets (user_id, category, monthly_limit) VALUES (?, ?, ?)", userId,
                    item.category(), Money.normalize(item.monthlyLimit()));
        }
        checkBudgets(userId);
        return budgets(userId);
    }

    /** Sends any 80% / 100% alert this month hasn't had yet for the user's budgets. */
    void checkBudgets(UUID userId) {
        List<BudgetItem> budgets = jdbc.query("SELECT category, monthly_limit FROM budgets WHERE user_id = ?",
                (rs, i) -> new BudgetItem(rs.getString(1), rs.getBigDecimal(2)), userId);
        if (budgets.isEmpty()) {
            return;
        }
        Map<String, BigDecimal> spent = spentThisMonth(userId);
        String month = YearMonth.from(today()).toString();
        for (BudgetItem b : budgets) {
            BigDecimal s = spent.getOrDefault(b.category(), BigDecimal.ZERO);
            BigDecimal p = pct(s, b.monthlyLimit());
            // The highest threshold crossed is the one worth telling; a jump straight past 100% skips the 80% alert.
            int threshold = p.compareTo(BigDecimal.valueOf(100)) >= 0 ? 100 : p.compareTo(BigDecimal.valueOf(80)) >= 0 ? 80 : 0;
            if (threshold == 0) {
                continue;
            }
            int inserted = jdbc.update("""
                    INSERT INTO budget_alerts (user_id, category, month, threshold) VALUES (?, ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, userId, b.category(), month, threshold);
            if (threshold == 100) {
                jdbc.update("INSERT INTO budget_alerts (user_id, category, month, threshold) VALUES (?, ?, ?, 80) "
                        + "ON CONFLICT DO NOTHING", userId, b.category(), month);
            }
            if (inserted == 1) {
                String label = CategoryService.label(b.category());
                notifications.notify(userId, NotificationType.BUDGET_ALERT,
                        threshold == 100 ? label + " budget used up" : label + " budget at 80%",
                        "You've spent ₹" + Money.toJson(Money.roundToPaise(s)) + " of your ₹"
                                + Money.toJson(b.monthlyLimit()) + " " + label.toLowerCase(java.util.Locale.ROOT)
                                + " budget this month.");
            }
        }
    }

    // ------------------------------------------------------------------ outbox consumer

    @Override
    public String name() {
        return "budget-alerts";
    }

    @Override
    public boolean accepts(String eventType) {
        return OutboxEvent.MONEY_MOVED.equals(eventType);
    }

    @Override
    public void handle(OutboxEvent event) {
        JsonNode p = event.payload();
        String type = p.path("type").asString("");
        if (!List.of(JournalType.TRANSFER.name(), JournalType.BILL_PAYMENT.name(), JournalType.LOAN_REPAYMENT.name(),
                JournalType.REVERSAL.name(), JournalType.CARD.name()).contains(type)) {
            return;
        }
        List<UUID> debited = new ArrayList<>();
        for (JsonNode line : p.path("lines")) {
            if ("DEBIT".equals(line.path("direction").asString())) {
                debited.add(UUID.fromString(line.path("ledgerAccountId").asString()));
            }
        }
        if (debited.isEmpty()) {
            return;
        }
        List<UUID> users = jdbc.queryForList("SELECT DISTINCT user_id FROM accounts WHERE id = ANY (?)", UUID.class,
                (Object) debited.toArray(new UUID[0]));
        for (UUID userId : users) {
            checkBudgets(userId);
        }
    }
}
