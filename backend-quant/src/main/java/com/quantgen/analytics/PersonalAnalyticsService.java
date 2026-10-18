package com.quantgen.analytics;

import static com.quantgen.analytics.AnalyticsDtos.*;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.insights.CategoryService;

/**
 * F-ANL-11..17: a customer's own numbers. Every query is filtered by the caller's user id, taken from the token and
 * never from the request, and another user's loan or account is a 404. It uses the same classification as the admin
 * view ({@link LedgerKinds}), so a customer's figures add up to the admin totals. Nothing here moves money.
 */
@Service
public class PersonalAnalyticsService {

    private static final MathContext MC = new MathContext(12);

    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    public PersonalAnalyticsService(NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static MapSqlParameterSource params(UUID userId, AnalyticsRange r) {
        return new MapSqlParameterSource("uid", userId).addValue("lo", Timestamp.from(r.lo()))
                .addValue("hi", Timestamp.from(r.hi()));
    }

    // ------------------------------------------------------------------ summary (F-ANL-11)

    private record Totals(BigDecimal income, BigDecimal spending, Map<String, BigDecimal> byKind) {
        BigDecimal net() {
            return income.subtract(spending);
        }
    }

    private Totals totals(UUID userId, AnalyticsRange r) {
        Map<String, BigDecimal> byKind = new HashMap<>();
        jdbc.query("WITH " + LedgerKinds.cl(true) + " SELECT kind, COALESCE(SUM(amt), 0) AS v FROM cl GROUP BY kind",
                params(userId, r), rs -> {
                    byKind.put(rs.getString("kind"), rs.getBigDecimal("v"));
                });
        BigDecimal income = LedgerKinds.sum(byKind, LedgerKinds.INCOME_KINDS);
        BigDecimal spending = LedgerKinds.sum(byKind, LedgerKinds.SPENDING_KINDS);
        return new Totals(income, spending, byKind);
    }

    private static SummaryTotals summaryTotals(Totals t) {
        BigDecimal rate = ratioPct(t.net(), t.income());
        return new SummaryTotals(money(t.income()), money(t.spending()), money(t.net()),
                rate == null ? null : pct(rate));
    }

    @Transactional(readOnly = true)
    public MoneySummaryDto summary(UUID userId, AnalyticsRange r) {
        Totals now = totals(userId, r);
        Totals before = totals(userId, r.previous());
        SummaryTotals cur = summaryTotals(now);

        // F-INS-1 categories: the biggest spending category in the period.
        List<TopCategory> tops = jdbc.query("WITH " + LedgerKinds.cl(true) + ", " + CategoryService.catCte() + """
                SELECT category, SUM(amt) AS v FROM cat WHERE kind IN (%s)
                 GROUP BY category HAVING SUM(amt) > 0 ORDER BY v DESC, category LIMIT 1
                """.formatted(LedgerKinds.SPENDING), params(userId, r), (rs, i) -> new TopCategory(
                rs.getString("category"), CategoryService.label(rs.getString("category")), money(rs.getBigDecimal("v"))));
        TopCategory top = tops.isEmpty() ? null : tops.getFirst();
        List<LargestSpend> largest = jdbc.query("WITH " + LedgerKinds.cl(true) + """
                SELECT line_id, amt, description, day FROM cl
                 WHERE kind IN (%s) AND sgn = 1 ORDER BY amt DESC, created_at DESC, line_id LIMIT 1
                """.formatted(LedgerKinds.SPENDING), params(userId, r), (rs, i) -> new LargestSpend(
                rs.getObject("line_id", UUID.class), money(rs.getBigDecimal("amt")), rs.getString("description"),
                rs.getDate("day").toLocalDate()));
        return new MoneySummaryDto(r.from(), r.to(), cur.income(), cur.spending(), cur.net(), cur.savingsRatePct(),
                summaryTotals(before), top, largest.isEmpty() ? null : largest.getFirst());
    }

    // ------------------------------------------------------------------ cash flow (F-ANL-12)

    @Transactional(readOnly = true)
    public CashflowDto cashflow(UUID userId, int months) {
        if (months < 1 || months > 24) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "months", "must be between 1 and 24");
        }
        YearMonth last = YearMonth.from(today());
        YearMonth first = last.minusMonths(months - 1L);
        AnalyticsRange r = new AnalyticsRange(first.atDay(1), last.atEndOfMonth());
        Map<String, Map<String, BigDecimal>> byMonth = new HashMap<>();
        jdbc.query("WITH " + LedgerKinds.cl(true) + """
                SELECT to_char(date_trunc('month', day), 'YYYY-MM') AS m, kind, COALESCE(SUM(amt), 0) AS v
                  FROM cl GROUP BY 1, 2
                """, params(userId, r), rs -> {
            byMonth.computeIfAbsent(rs.getString("m"), k -> new HashMap<>()).put(rs.getString("kind"),
                    rs.getBigDecimal("v"));
        });
        List<CashflowPoint> points = new ArrayList<>();
        for (YearMonth m = first; !m.isAfter(last); m = m.plusMonths(1)) {
            Map<String, BigDecimal> k = byMonth.getOrDefault(m.toString(), Map.of());
            BigDecimal income = LedgerKinds.sum(k, LedgerKinds.INCOME_KINDS);
            BigDecimal spending = LedgerKinds.sum(k, LedgerKinds.SPENDING_KINDS);
            points.add(new CashflowPoint(m.toString(), money(income), money(spending), money(income.subtract(spending)),
                    money(k.getOrDefault("INVESTED", BigDecimal.ZERO)), money(k.getOrDefault("BORROWED", BigDecimal.ZERO))));
        }
        return new CashflowDto(points);
    }

    // ------------------------------------------------------------------ counterparties (F-ANL-13)

    @Transactional(readOnly = true)
    public List<CounterpartyDto> counterparties(UUID userId, AnalyticsRange r, int limit) {
        int cap = Math.max(1, Math.min(limit, 20));
        return jdbc.query("""
                SELECT oa.account_number AS num, ou.full_name AS name,
                       SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE 0 END) AS sent,
                       SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE 0 END) AS received,
                       COUNT(DISTINCT e.id) AS cnt
                  FROM ledger_lines l
                  JOIN accounts a ON a.id = l.ledger_account_id AND a.user_id = :uid
                  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.type = 'TRANSFER'
                  JOIN ledger_lines ol ON ol.journal_entry_id = e.id AND ol.id <> l.id
                  JOIN accounts oa ON oa.id = ol.ledger_account_id AND oa.user_id <> a.user_id
                  JOIN users ou ON ou.id = oa.user_id
                 WHERE l.created_at >= :lo AND l.created_at < :hi
                 GROUP BY oa.account_number, ou.full_name
                 ORDER BY SUM(l.amount) DESC, oa.account_number
                 LIMIT :limit
                """, params(userId, r).addValue("limit", cap), (rs, i) -> new CounterpartyDto(
                maskName(rs.getString("name")), AccountNumbers.mask(rs.getString("num")),
                money(rs.getBigDecimal("sent")), money(rs.getBigDecimal("received")), rs.getLong("cnt")));
    }

    /** "Aarav Mehta" becomes "Aarav M.": enough to recognise, not enough to identify. */
    static String maskName(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length < 2) {
            return parts[0];
        }
        return parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    // ------------------------------------------------------------------ balance history (F-ANL-14)

    @Transactional(readOnly = true)
    public BalanceHistoryDto balanceHistory(UUID userId, UUID accountId, String range) {
        int days = switch (range == null ? "1M" : range.toUpperCase()) {
            case "1M" -> 30;
            case "3M" -> 90;
            case "6M" -> 180;
            case "1Y" -> 365;
            default -> throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "range", "must be 1M, 3M, 6M or 1Y");
        };
        if (accountId != null && Boolean.FALSE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM accounts WHERE id = :aid AND user_id = :uid)",
                new MapSqlParameterSource("aid", accountId).addValue("uid", userId), Boolean.class))) {
            throw ApiException.notFound("Account");
        }
        LocalDate to = today();
        AnalyticsRange r = new AnalyticsRange(to.minusDays(days - 1L), to);
        MapSqlParameterSource p = params(userId, r).addValue("aid", accountId);
        String accounts = "SELECT a.id FROM accounts a WHERE a.user_id = :uid AND (CAST(:aid AS uuid) IS NULL OR a.id = :aid)";
        BigDecimal balance = jdbc.queryForObject("SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount "
                + "ELSE -l.amount END), 0) FROM ledger_lines l WHERE l.ledger_account_id IN (" + accounts
                + ") AND l.created_at < :lo", p, BigDecimal.class);
        Map<LocalDate, BigDecimal> net = new HashMap<>();
        jdbc.query("SELECT (l.created_at AT TIME ZONE 'Asia/Kolkata')::date AS d, SUM(CASE l.direction WHEN 'CREDIT' "
                + "THEN l.amount ELSE -l.amount END) AS v FROM ledger_lines l WHERE l.ledger_account_id IN ("
                + accounts + ") AND l.created_at >= :lo AND l.created_at < :hi GROUP BY 1", p, rs -> {
            net.put(rs.getDate("d").toLocalDate(), rs.getBigDecimal("v"));
        });
        List<BalancePoint> points = new ArrayList<>();
        for (LocalDate d = r.from(); !d.isAfter(r.to()); d = d.plusDays(1)) {
            balance = balance.add(net.getOrDefault(d, BigDecimal.ZERO));
            points.add(new BalancePoint(d, money(balance)));
        }
        return new BalanceHistoryDto(accountId, points);
    }

    // ------------------------------------------------------------------ portfolio (F-ANL-15)

    @Transactional(readOnly = true)
    public PortfolioDto portfolio(UUID userId, String range) {
        String key = range == null ? "1M" : range.toUpperCase();
        LocalDate today = today();
        LocalDate from = switch (key) {
            case "1M" -> today.minusDays(29);
            case "3M" -> today.minusDays(89);
            case "6M" -> today.minusDays(179);
            case "1Y" -> today.minusDays(364);
            case "ALL" -> LocalDate.of(2000, 1, 1);
            default -> throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "range", "must be 1M, 3M, 6M, 1Y or ALL");
        };
        record Pos(String symbol, String name, String sector, long qty, BigDecimal cost, BigDecimal realized,
                   BigDecimal price) {
        }
        List<Pos> positions = jdbc.query("""
                SELECT h.symbol, i.name, i.sector, h.quantity, h.cost_basis, h.realized_pnl, i.price
                  FROM holdings h JOIN instruments i ON i.symbol = h.symbol
                 WHERE h.user_id = :uid AND (h.quantity > 0 OR h.realized_pnl <> 0) ORDER BY h.symbol
                """, new MapSqlParameterSource("uid", userId), (rs, i) -> new Pos(rs.getString("symbol"),
                rs.getString("name"), rs.getString("sector"), rs.getLong("quantity"), rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("realized_pnl"), rs.getBigDecimal("price")));

        BigDecimal marketValue = BigDecimal.ZERO;
        BigDecimal invested = BigDecimal.ZERO;
        Map<String, BigDecimal> bySector = new TreeMap<>();
        List<HoldingPerf> holdings = new ArrayList<>();
        PerfMark best = null;
        PerfMark worst = null;
        BigDecimal bestPct = null;
        BigDecimal worstPct = null;
        for (Pos p : positions) {
            BigDecimal value = p.price().multiply(BigDecimal.valueOf(p.qty()));
            BigDecimal unrealized = value.subtract(p.cost());
            BigDecimal unrealizedPct = p.qty() > 0 && p.cost().signum() > 0
                    ? ratioPct(unrealized, p.cost()) : BigDecimal.ZERO;
            holdings.add(new HoldingPerf(p.symbol(), p.name(), money(p.qty() > 0 ? unrealized : BigDecimal.ZERO),
                    pct(unrealizedPct), money(p.realized())));
            if (p.qty() > 0) {
                marketValue = marketValue.add(value);
                invested = invested.add(p.cost());
                bySector.merge(p.sector(), value, BigDecimal::add);
                if (bestPct == null || unrealizedPct.compareTo(bestPct) > 0) {
                    bestPct = unrealizedPct;
                    best = new PerfMark(p.symbol(), pct(unrealizedPct));
                }
                if (worstPct == null || unrealizedPct.compareTo(worstPct) < 0) {
                    worstPct = unrealizedPct;
                    worst = new PerfMark(p.symbol(), pct(unrealizedPct));
                }
            }
        }
        final BigDecimal total = marketValue;
        List<AllocationRow> allocation = bySector.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .map(e -> new AllocationRow(e.getKey(), money(e.getValue()),
                        pct(ratioPct(e.getValue(), total) == null ? BigDecimal.ZERO : ratioPct(e.getValue(), total))))
                .toList();

        // History: the nightly snapshots, plus today's live value.
        Map<LocalDate, BigDecimal[]> series = new TreeMap<>();
        jdbc.query("SELECT day, market_value, invested FROM portfolio_snapshots WHERE user_id = :uid "
                        + "AND day >= :from AND day < :today ORDER BY day",
                new MapSqlParameterSource("uid", userId).addValue("from", java.sql.Date.valueOf(from))
                        .addValue("today", java.sql.Date.valueOf(today)), rs -> {
                    series.put(rs.getDate("day").toLocalDate(),
                            new BigDecimal[]{rs.getBigDecimal("market_value"), rs.getBigDecimal("invested")});
                });
        if (!positions.isEmpty()) {
            series.put(today, new BigDecimal[]{marketValue, invested});
        }
        List<PortfolioPoint> points = new ArrayList<>();
        series.forEach((d, v) -> points.add(new PortfolioPoint(d, money(v[0]), money(v[1]))));

        return new PortfolioDto(points, pct(timeWeightedReturn(userId, series)), allocation, holdings, best, worst);
    }

    /**
     * Time-weighted return over the snapshots: each day's growth is measured on the value at the start of that day
     * plus that day's net purchases, so adding money does not look like performance. Days without a usable base
     * are skipped.
     */
    private BigDecimal timeWeightedReturn(UUID userId, Map<LocalDate, BigDecimal[]> series) {
        if (series.size() < 2) {
            return BigDecimal.ZERO;
        }
        LocalDate first = series.keySet().iterator().next();
        Map<LocalDate, BigDecimal> flows = new HashMap<>();
        jdbc.query("""
                SELECT (created_at AT TIME ZONE 'Asia/Kolkata')::date AS d,
                       SUM(CASE side WHEN 'BUY' THEN total ELSE -total END) AS v
                  FROM orders WHERE user_id = :uid AND status = 'FILLED' AND created_at >= :lo GROUP BY 1
                """, new MapSqlParameterSource("uid", userId).addValue("lo",
                Timestamp.from(first.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant())), rs -> {
            flows.put(rs.getDate("d").toLocalDate(), rs.getBigDecimal("v"));
        });
        BigDecimal growth = BigDecimal.ONE;
        BigDecimal prev = null;
        for (Map.Entry<LocalDate, BigDecimal[]> e : series.entrySet()) {
            BigDecimal mv = e.getValue()[0];
            if (prev != null) {
                BigDecimal base = prev.add(flows.getOrDefault(e.getKey(), BigDecimal.ZERO));
                if (base.signum() > 0) {
                    growth = growth.multiply(mv.divide(base, MC), MC);
                }
            }
            prev = mv;
        }
        return growth.subtract(BigDecimal.ONE).multiply(BigDecimal.valueOf(100));
    }

    // ------------------------------------------------------------------ loans (F-ANL-16)

    @Transactional(readOnly = true)
    public LoanAnalyticsDto loans(UUID userId) {
        List<LoanProgress> loans = jdbc.query("""
                SELECT l.id, l.status, l.outstanding_principal,
                       COALESCE(SUM(r.principal_paid), 0) AS pp, COALESCE(SUM(r.interest_paid), 0) AS ip,
                       (SELECT MAX(i.due_date) FROM loan_installments i WHERE i.loan_id = l.id AND i.status <> 'PAID') AS payoff
                  FROM loans l LEFT JOIN loan_repayments r ON r.loan_id = l.id
                 WHERE l.user_id = :uid AND l.status IN ('ACTIVE', 'CLOSED')
                 GROUP BY l.id ORDER BY l.created_at, l.id
                """, new MapSqlParameterSource("uid", userId), (rs, i) -> new LoanProgress(rs.getObject("id", UUID.class),
                money(rs.getBigDecimal("pp")), money(rs.getBigDecimal("ip")),
                money(rs.getBigDecimal("outstanding_principal")),
                "ACTIVE".equals(rs.getString("status")) && rs.getDate("payoff") != null
                        ? rs.getDate("payoff").toLocalDate() : null));
        BigDecimal interest = loans.stream().map(l -> new BigDecimal(l.interestPaid())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LoanAnalyticsDto(money(interest), loans);
    }

    /** What if the customer prepaid {@code amount} now and kept paying the same EMI? Calculation only. */
    @Transactional(readOnly = true)
    public PrepaymentDto prepayment(UUID userId, UUID loanId, BigDecimal amount) {
        record LoanRow(String status, BigDecimal outstanding, BigDecimal rate, BigDecimal emi) {
        }
        List<LoanRow> found = jdbc.query("SELECT status, outstanding_principal, annual_rate, emi FROM loans "
                        + "WHERE id = :id AND user_id = :uid",
                new MapSqlParameterSource("id", loanId).addValue("uid", userId),
                (rs, i) -> new LoanRow(rs.getString("status"), rs.getBigDecimal("outstanding_principal"),
                        rs.getBigDecimal("annual_rate"), rs.getBigDecimal("emi")));
        if (found.isEmpty()) {
            throw ApiException.notFound("Loan"); // another user's loan looks exactly like a missing one
        }
        LoanRow loan = found.getFirst();
        if (!"ACTIVE".equals(loan.status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only an active loan can be prepaid");
        }
        BigDecimal x = amount.setScale(2, RoundingMode.UNNECESSARY);
        java.sql.Date lastDueDate = jdbc.queryForObject("SELECT MAX(due_date) FROM loan_installments "
                + "WHERE loan_id = :id AND status <> 'PAID'", new MapSqlParameterSource("id", loanId),
                java.sql.Date.class);
        LocalDate lastDue = lastDueDate == null ? today() : lastDueDate.toLocalDate();

        BigDecimal monthlyRate = loan.rate().divide(BigDecimal.valueOf(1200), MC);
        Sim base = simulate(loan.outstanding(), loan.emi(), monthlyRate);
        if (x.compareTo(loan.outstanding()) >= 0) {
            return new PrepaymentDto(money(base.interest()), base.months(), today());
        }
        Sim after = simulate(loan.outstanding().subtract(x), loan.emi(), monthlyRate);
        int saved = Math.max(0, base.months() - after.months());
        BigDecimal interestSaved = base.interest().subtract(after.interest()).max(BigDecimal.ZERO);
        return new PrepaymentDto(money(interestSaved), saved, lastDue.minusMonths(saved));
    }

    private record Sim(int months, BigDecimal interest) {
    }

    /** Pay the same EMI each month until the balance clears; the last payment is whatever is left. */
    private static Sim simulate(BigDecimal balance, BigDecimal emi, BigDecimal monthlyRate) {
        BigDecimal remaining = balance;
        BigDecimal interestTotal = BigDecimal.ZERO;
        int months = 0;
        while (remaining.signum() > 0 && months < 600) {
            BigDecimal interest = remaining.multiply(monthlyRate, MC);
            BigDecimal principal = emi.subtract(interest);
            if (principal.signum() <= 0) {
                break; // the EMI never pays the loan down; not reachable for loans we issued
            }
            interestTotal = interestTotal.add(interest);
            remaining = remaining.subtract(principal);
            months++;
        }
        return new Sim(months, interestTotal);
    }

    // ------------------------------------------------------------------ goals (V2-M3)

    /** Days of history behind {@code monthlyPace}. */
    static final int PACE_DAYS = 90;

    /**
     * F-SAV-1 goal progress. {@code monthlyPace} is the net amount added over the last 90 days, per 30 days;
     * {@code projectedDate} extrapolates it (null when the pace isn't positive, today when the target is reached);
     * {@code onTrack} means reached, or projected to reach it by {@code targetDate} (any positive pace when there is
     * no target date).
     */
    @Transactional(readOnly = true)
    public List<GoalProgressDto> goals(UUID userId) {
        LocalDate today = today();
        Instant since = today.minusDays(PACE_DAYS - 1L).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return jdbc.query("""
                SELECT a.id, g.name, g.target, g.target_date, la.balance,
                       COALESCE((SELECT SUM(CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END)
                                   FROM ledger_lines l WHERE l.ledger_account_id = a.id AND l.created_at >= :since), 0)
                           AS added
                  FROM accounts a JOIN goals g ON g.account_id = a.id JOIN ledger_accounts la ON la.id = a.id
                 WHERE a.user_id = :uid AND a.status <> 'CLOSED' ORDER BY a.created_at, a.id
                """, new MapSqlParameterSource().addValue("uid", userId).addValue("since", Timestamp.from(since)),
                (rs, i) -> {
                    BigDecimal target = rs.getBigDecimal("target");
                    BigDecimal saved = rs.getBigDecimal("balance");
                    java.sql.Date td = rs.getDate("target_date");
                    LocalDate targetDate = td == null ? null : td.toLocalDate();
                    BigDecimal pace = rs.getBigDecimal("added").multiply(BigDecimal.valueOf(30))
                            .divide(BigDecimal.valueOf(PACE_DAYS), 2, RoundingMode.HALF_EVEN);
                    boolean reached = saved.compareTo(target) >= 0;
                    LocalDate projected = null;
                    if (reached) {
                        projected = today;
                    } else if (pace.signum() > 0) {
                        long days = target.subtract(saved).multiply(BigDecimal.valueOf(30))
                                .divide(pace, 0, RoundingMode.CEILING).longValueExact();
                        projected = today.plusDays(Math.min(days, 365L * 100));
                    }
                    boolean onTrack = reached || (projected != null
                            && (targetDate == null || !projected.isAfter(targetDate)));
                    return new GoalProgressDto(rs.getObject("id", UUID.class), rs.getString("name"), money(target),
                            money(saved), pct(saved.multiply(BigDecimal.valueOf(100))
                            .divide(target, 2, RoundingMode.HALF_EVEN)), money(pace), projected, onTrack);
                });
    }
}
