package com.quantgen.insights;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Money;

/**
 * F-INS-3 cash-flow forecast and F-INS-4 net worth. Both read balances straight from the ledger; past values are
 * reconstructed as {@code balance now − lines since}, so they need no snapshots (portfolio history excepted, which
 * uses the nightly portfolio snapshots).
 */
@Service
public class PlanningService {

    public static final BigDecimal LOW_BALANCE = new BigDecimal("1000.00");
    /** An incoming payment counts as salary from the same person in at least this many of the last 3 months. */
    static final int SALARY_MONTHS = 2;
    static final BigDecimal SALARY_MIN = new BigDecimal("1000.00");

    public record Breakdown(BigDecimal cash, BigDecimal goals, BigDecimal fixedDeposits, BigDecimal investments,
                            BigDecimal loans) {
        BigDecimal total() {
            return cash.add(goals).add(fixedDeposits).add(investments).subtract(loans);
        }
    }

    public record NetWorthPoint(String month, BigDecimal value) {
    }

    public record NetWorthDto(BigDecimal current, Breakdown breakdown, List<NetWorthPoint> history) {
    }

    public record ForecastPoint(LocalDate date, BigDecimal balance) {
    }

    public record ForecastWarning(LocalDate date, String code, String message) {
    }

    public record ForecastDto(List<ForecastPoint> points, List<ForecastWarning> warnings) {
    }

    /** A known future money movement for the forecast; {@code amount} is signed (+ in, − out). */
    record Item(LocalDate date, BigDecimal amount, String kind, String label) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PlanningService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    // ------------------------------------------------------------------ net worth

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public NetWorthDto netWorth(UUID userId, int months) {
        if (months < 1 || months > 24) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "months", "must be between 1 and 24");
        }
        Breakdown now = breakdownAt(userId, null);
        YearMonth current = YearMonth.from(today());
        List<NetWorthPoint> history = new ArrayList<>();
        for (YearMonth m = current.minusMonths(months - 1L); !m.isAfter(current); m = m.plusMonths(1)) {
            BigDecimal value = m.equals(current) ? now.total()
                    : breakdownAt(userId, m.plusMonths(1).atDay(1)).total();
            history.add(new NetWorthPoint(m.toString(), Money.roundToPaise(value)));
        }
        return new NetWorthDto(Money.roundToPaise(now.total()), now, history);
    }

    /** The breakdown at the start of {@code day} (IST), or now when null. */
    Breakdown breakdownAt(UUID userId, LocalDate day) {
        Instant at = day == null ? Instant.now(clock) : day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Timestamp ts = Timestamp.from(at);
        Map<String, BigDecimal> byType = new HashMap<>();
        jdbc.query("""
                SELECT a.type, SUM(la.balance - COALESCE((
                         SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                           FROM ledger_lines l WHERE l.ledger_account_id = a.id AND l.created_at >= ?), 0)) AS v
                  FROM accounts a JOIN ledger_accounts la ON la.id = a.id
                 WHERE a.user_id = ? AND a.created_at < ? GROUP BY a.type
                """, rs -> {
            byType.put(rs.getString(1), rs.getBigDecimal(2));
        }, ts, userId, ts);
        BigDecimal cash = byType.getOrDefault("SAVINGS", BigDecimal.ZERO)
                .add(byType.getOrDefault("CHECKING", BigDecimal.ZERO));
        BigDecimal investments = day == null
                ? jdbc.queryForObject("""
                        SELECT COALESCE(SUM(h.quantity * i.price), 0) FROM holdings h
                          JOIN instruments i ON i.symbol = h.symbol WHERE h.user_id = ?
                        """, BigDecimal.class, userId)
                : jdbc.query("SELECT market_value FROM portfolio_snapshots WHERE user_id = ? AND day < ? "
                        + "ORDER BY day DESC LIMIT 1", (rs, i) -> rs.getBigDecimal(1), userId, Date.valueOf(day))
                        .stream().findFirst().orElse(BigDecimal.ZERO);
        BigDecimal loans = jdbc.queryForObject("""
                SELECT COALESCE(SUM(l.amount - COALESCE((SELECT SUM(r.principal_paid) FROM loan_repayments r
                                                          WHERE r.loan_id = l.id AND r.created_at < ?), 0)), 0)
                  FROM loans l WHERE l.user_id = ? AND l.disbursed_at IS NOT NULL AND l.disbursed_at < ?
                """, BigDecimal.class, ts, userId, ts);
        return new Breakdown(Money.roundToPaise(cash),
                Money.roundToPaise(byType.getOrDefault("GOAL", BigDecimal.ZERO)),
                Money.roundToPaise(byType.getOrDefault("FIXED_DEPOSIT", BigDecimal.ZERO)),
                Money.roundToPaise(investments), Money.roundToPaise(loans));
    }

    // ------------------------------------------------------------------ forecast

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ForecastDto forecast(UUID userId, int days) {
        if (days < 1 || days > 90) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "days", "must be between 1 and 90");
        }
        LocalDate today = today();
        LocalDate last = today.plusDays(days);
        // Spendable money now: available balance (balance − holds) of open everyday accounts.
        BigDecimal balance = jdbc.queryForObject("""
                SELECT COALESCE(SUM(la.balance - COALESCE((SELECT SUM(h.amount) FROM holds h
                                                           WHERE h.ledger_account_id = a.id AND h.status = 'ACTIVE'), 0)), 0)
                  FROM accounts a JOIN ledger_accounts la ON la.id = a.id
                 WHERE a.user_id = ? AND a.type IN ('SAVINGS', 'CHECKING') AND a.status <> 'CLOSED'
                """, BigDecimal.class, userId);
        List<Item> items = new ArrayList<>();
        emis(userId, today, last, items);
        scheduled(userId, today, last, items);
        fdPayouts(userId, today, last, items);
        salary(userId, today, last, items);

        TreeMap<LocalDate, List<Item>> byDay = new TreeMap<>();
        items.forEach(i -> byDay.computeIfAbsent(i.date(), d -> new ArrayList<>()).add(i));
        List<ForecastPoint> points = new ArrayList<>();
        List<ForecastWarning> warnings = new ArrayList<>();
        boolean lowWarned = false;
        BigDecimal running = balance;
        points.add(new ForecastPoint(today, Money.roundToPaise(running)));
        for (LocalDate d = today.plusDays(1); !d.isAfter(last); d = d.plusDays(1)) {
            List<Item> dayItems = byDay.getOrDefault(d, List.of());
            // Money in arrives before money goes out on the same day.
            for (Item in : dayItems.stream().filter(i -> i.amount().signum() > 0).toList()) {
                running = running.add(in.amount());
            }
            for (Item out : dayItems.stream().filter(i -> i.amount().signum() < 0).toList()) {
                if (running.add(out.amount()).signum() < 0) {
                    String code = "EMI".equals(out.kind()) ? "EMI_AT_RISK" : "SCHEDULED_AT_RISK";
                    warnings.add(new ForecastWarning(d, code, out.label() + " of ₹" + Money.toJson(out.amount().negate())
                            + " may fail: your projected balance is ₹" + Money.toJson(Money.roundToPaise(running)) + "."));
                }
                running = running.add(out.amount());
            }
            if (!lowWarned && running.compareTo(LOW_BALANCE) < 0) {
                lowWarned = true;
                warnings.add(new ForecastWarning(d, "LOW_BALANCE", "Your balance is projected to fall to ₹"
                        + Money.toJson(Money.roundToPaise(running)) + ", below ₹" + Money.toJson(LOW_BALANCE) + "."));
            }
            points.add(new ForecastPoint(d, Money.roundToPaise(running)));
        }
        return new ForecastDto(points, warnings);
    }

    /** EMIs still owed on ACTIVE loans; an overdue one is expected tomorrow (the auto-debit retries daily). */
    private void emis(UUID userId, LocalDate today, LocalDate last, List<Item> items) {
        jdbc.query("""
                SELECT i.due_date, i.emi - i.principal_paid - i.interest_paid AS due FROM loan_installments i
                  JOIN loans l ON l.id = i.loan_id
                 WHERE l.user_id = ? AND l.status = 'ACTIVE' AND i.status <> 'PAID' AND i.due_date <= ?
                """, rs -> {
            LocalDate due = rs.getDate(1).toLocalDate();
            BigDecimal amount = rs.getBigDecimal(2);
            if (amount.signum() > 0) {
                items.add(new Item(due.isAfter(today) ? due : today.plusDays(1), amount.negate(), "EMI", "Loan EMI"));
            }
        }, userId, Date.valueOf(last));
    }

    /**
     * ACTIVE scheduled transfers that change the user's everyday money: out to someone else or to one of their
     * goals; in from someone else, or from one of their goals. Moves between their own everyday accounts net out.
     */
    private void scheduled(UUID userId, LocalDate today, LocalDate last, List<Item> items) {
        jdbc.query("""
                SELECT s.amount, s.frequency, COALESCE(s.retry_on, s.next_run_date) AS next, s.end_date,
                       s.remaining_count,
                       f.user_id = ? AND f.type IN ('SAVINGS', 'CHECKING') AS from_everyday,
                       t.user_id = ? AND t.type IN ('SAVINGS', 'CHECKING') AS to_everyday
                  FROM scheduled_transfers s
                  JOIN accounts f ON f.id = s.from_account_id JOIN accounts t ON t.id = s.to_account_id
                 WHERE s.status = 'ACTIVE' AND (s.user_id = ? OR t.user_id = ?)
                """, rs -> {
            boolean fromEveryday = rs.getBoolean("from_everyday");
            boolean toEveryday = rs.getBoolean("to_everyday");
            if (fromEveryday == toEveryday) {
                return; // own everyday ↔ everyday, or nothing of the user's everyday money involved
            }
            BigDecimal amount = fromEveryday ? rs.getBigDecimal("amount").negate() : rs.getBigDecimal("amount");
            String frequency = rs.getString("frequency");
            Date end = rs.getDate("end_date");
            Integer remaining = (Integer) rs.getObject("remaining_count");
            LocalDate d = rs.getDate("next").toLocalDate();
            int n = 0;
            while (!d.isAfter(last) && (end == null || !d.isAfter(end.toLocalDate()))
                    && (remaining == null || n < remaining)) {
                items.add(new Item(d.isAfter(today) ? d : today.plusDays(1), amount, "SCHEDULED", "Scheduled transfer"));
                n++;
                if ("ONCE".equals(frequency)) {
                    break;
                }
                d = "WEEKLY".equals(frequency) ? d.plusWeeks(1) : d.plusMonths(1);
            }
        }, userId, userId, userId, userId);
    }

    private void fdPayouts(UUID userId, LocalDate today, LocalDate last, List<Item> items) {
        jdbc.query("""
                SELECT maturity_date, principal, annual_rate, tenure_days FROM fixed_deposits
                 WHERE user_id = ? AND status = 'ACTIVE' AND on_maturity = 'PAYOUT' AND maturity_date <= ?
                """, rs -> {
            LocalDate d = rs.getDate(1).toLocalDate();
            BigDecimal principal = rs.getBigDecimal(2);
            BigDecimal interest = com.quantgen.savings.FixedDepositService.interestFor(principal, rs.getBigDecimal(3),
                    rs.getInt(4));
            items.add(new Item(d.isAfter(today) ? d : today.plusDays(1), principal.add(interest), "FD", "FD payout"));
        }, userId, Date.valueOf(last));
    }

    /**
     * Detected salary: money from the same person's account in at least 2 of the last 3 calendar-month windows
     * (30 days each), each time ₹1,000 or more. The next one is expected a month after the latest, for its amount.
     */
    private void salary(UUID userId, LocalDate today, LocalDate last, List<Item> items) {
        Timestamp since = Timestamp.from(today.minusDays(90).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
        record Payment(UUID from, LocalDate day, BigDecimal amount) {
        }
        List<Payment> payments = jdbc.query("""
                SELECT ol.ledger_account_id AS src, (l.created_at AT TIME ZONE 'Asia/Kolkata')::date AS day, l.amount
                  FROM ledger_lines l
                  JOIN accounts a ON a.id = l.ledger_account_id
                  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.type = 'TRANSFER'
                  JOIN ledger_lines ol ON ol.journal_entry_id = l.journal_entry_id AND ol.direction = 'DEBIT'
                  JOIN accounts oa ON oa.id = ol.ledger_account_id AND oa.user_id <> a.user_id
                 WHERE a.user_id = ? AND a.type IN ('SAVINGS', 'CHECKING') AND l.direction = 'CREDIT'
                   AND l.created_at >= ? AND l.amount >= ?
                 ORDER BY l.created_at
                """, (rs, i) -> new Payment(rs.getObject(1, UUID.class), rs.getDate(2).toLocalDate(),
                rs.getBigDecimal(3)), userId, since, SALARY_MIN);
        Map<UUID, List<Payment>> bySource = new HashMap<>();
        payments.forEach(p -> bySource.computeIfAbsent(p.from(), k -> new ArrayList<>()).add(p));
        for (List<Payment> ps : bySource.values()) {
            long windows = ps.stream().map(p -> ChronoUnit.DAYS.between(p.day(), today) / 30).distinct().count();
            if (windows < SALARY_MONTHS) {
                continue;
            }
            Payment latest = ps.getLast();
            for (LocalDate d = latest.day().plusMonths(1); !d.isAfter(last); d = d.plusMonths(1)) {
                if (d.isAfter(today)) {
                    items.add(new Item(d, latest.amount(), "SALARY", "Expected salary"));
                }
            }
        }
    }
}
