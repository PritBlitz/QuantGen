package com.quantgen.analytics;

import static com.quantgen.analytics.AnalyticsDtos.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.insights.CategoryService;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.ReconcileReport;

/**
 * F-ANL-1..10: business analytics for admins. Read-only, aggregate-only: nothing here returns a name, email,
 * phone or account number, and any breakdown or cohort cell covering fewer than {@link #MIN_CELL_USERS} distinct
 * users is returned suppressed with a null value (F-ANL-10). Figures come straight from the ledger and domain
 * tables through the shared classification in {@link LedgerKinds}, so they agree with the customer view.
 */
@Service
public class AdminAnalyticsService {

    /** F-ANL-10: cells with fewer users than this are suppressed. */
    public static final int MIN_CELL_USERS = 5;
    private static final String TZ = "'Asia/Kolkata'";
    private static final long HEALTH_TTL_MILLIS = 60_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final LedgerService ledger;
    private final Clock clock;

    private volatile Health cachedHealth;

    public AdminAnalyticsService(NamedParameterJdbcTemplate jdbc, LedgerService ledger, Clock clock) {
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.clock = clock;
    }

    private record Health(long at, boolean balanced) {
    }

    // ------------------------------------------------------------------ overview (F-ANL-1)

    private static final List<Metric> OVERVIEW = List.of(Metric.TOTAL_USERS, Metric.NEW_USERS, Metric.ACTIVE_USERS,
            Metric.FUNDED_USERS_PCT, Metric.CUSTOMER_DEPOSITS, Metric.NET_INFLOW, Metric.TRANSFER_VOLUME,
            Metric.TRANSFER_COUNT, Metric.TRADE_VOLUME, Metric.TRADE_COUNT, Metric.OUTSTANDING_PRINCIPAL,
            Metric.INTEREST_INCOME, Metric.OVERDUE_RATE_PCT, Metric.FAILED_MONEY_REQUESTS);

    @Transactional(readOnly = true)
    public OverviewDto overview(AnalyticsRange range) {
        AnalyticsRange prev = range.previous();
        Memo memo = new Memo();
        List<KpiDto> kpis = new ArrayList<>();
        for (Metric m : OVERVIEW) {
            BigDecimal cur = value(m, range, memo);
            BigDecimal old = value(m, prev, memo);
            kpis.add(new KpiDto(m.name(), m.label, m.unit, format(m.unit, cur), format(m.unit, old),
                    changePct(cur, old), m.good));
        }
        return new OverviewDto(range.from(), range.to(), prev.from(), prev.to(), kpis);
    }

    /** Caches the flow totals of a range within one request, since five KPIs read the same query. */
    private static final class Memo {
        final Map<AnalyticsRange, Map<String, BigDecimal[]>> flows = new HashMap<>();
    }

    private BigDecimal value(Metric m, AnalyticsRange r, Memo memo) {
        return switch (m) {
            case TOTAL_USERS -> usersBefore(r.hi());
            case NEW_USERS -> scalar("SELECT count(*) FROM users WHERE role = 'CUSTOMER' "
                    + "AND created_at >= :lo AND created_at < :hi", r);
            case ACTIVE_USERS -> scalar("WITH " + LedgerKinds.cl(false) + """
                    SELECT count(*) FROM (
                      SELECT user_id FROM cl
                      UNION
                      SELECT rt.user_id FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id AND u.role = 'CUSTOMER'
                       WHERE rt.created_at >= :lo AND rt.created_at < :hi
                    ) t
                    """, r);
            case FUNDED_USERS_PCT -> {
                BigDecimal total = usersBefore(r.hi());
                BigDecimal pct = ratioPct(fundedBefore(r.hi()), total);
                yield pct == null ? BigDecimal.ZERO : pct;
            }
            case CUSTOMER_DEPOSITS -> depositsBefore(r.hi());
            case NET_INFLOW -> flow(r, memo, "DEPOSIT")[0].add(flow(r, memo, "TOPUP")[0]);
            case TRANSFER_VOLUME -> flow(r, memo, "TRANSFER_OUT")[0];
            case TRANSFER_COUNT -> flow(r, memo, "TRANSFER_OUT")[1];
            case TRADE_VOLUME -> flow(r, memo, "INVESTED")[0].add(flow(r, memo, "DIVESTED")[0]);
            case TRADE_COUNT -> flow(r, memo, "INVESTED")[1].add(flow(r, memo, "DIVESTED")[1]);
            case OUTSTANDING_PRINCIPAL -> outstandingBefore(r.hi());
            case LOAN_DISBURSED -> scalar("SELECT COALESCE(SUM(amount), 0) FROM loans "
                    + "WHERE disbursed_at >= :lo AND disbursed_at < :hi", r);
            case LOAN_REPAID -> scalar("SELECT COALESCE(SUM(amount), 0) FROM loan_repayments "
                    + "WHERE created_at >= :lo AND created_at < :hi", r);
            case INTEREST_INCOME -> scalar("SELECT COALESCE(SUM(interest_paid), 0) FROM loan_repayments "
                    + "WHERE created_at >= :lo AND created_at < :hi", r);
            case OVERDUE_AMOUNT -> overdueAsOf(r.to(), r.hi());
            case OVERDUE_RATE_PCT -> {
                BigDecimal pct = ratioPct(overdueAsOf(r.to(), r.hi()), outstandingBefore(r.hi()));
                yield pct == null ? BigDecimal.ZERO : pct;
            }
            case FAILED_MONEY_REQUESTS -> scalar("SELECT count(*) FROM request_failures "
                    + "WHERE created_at >= :lo AND created_at < :hi", r);
            default -> BigDecimal.ZERO; // v2 metrics whose milestone has not shipped
        };
    }

    // ------------------------------------------------------------------ time series (F-ANL-2)

    @Transactional(readOnly = true)
    public SeriesDto timeseries(Metric metric, AnalyticsRange range, Granularity gran, boolean compare) {
        if (!metric.series) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "metric", "is not available as a time series");
        }
        if (!metric.available) {
            return new SeriesDto(metric.name(), metric.label, metric.unit, gran, false, List.of(),
                    compare ? List.of() : null);
        }
        return new SeriesDto(metric.name(), metric.label, metric.unit, gran, true, series(metric, range, gran),
                compare ? series(metric, range.previous(), gran) : null);
    }

    private List<SeriesPoint> series(Metric m, AnalyticsRange r, Granularity g) {
        String unit = g.name().toLowerCase();
        String bucket = "date_trunc('" + unit + "', %s)::date";
        Map<LocalDate, BigDecimal> data = switch (m) {
            case NEW_USERS -> byBucket("SELECT " + bucket.formatted("(created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, count(*) v FROM users WHERE role = 'CUSTOMER' AND created_at >= :lo AND created_at < :hi "
                    + "GROUP BY 1", r);
            case ACTIVE_USERS -> byBucket("WITH " + LedgerKinds.cl(false) + " SELECT " + bucket.formatted("day")
                    + " b, count(DISTINCT user_id) v FROM (SELECT user_id, day FROM cl UNION ALL "
                    + "SELECT rt.user_id, (rt.created_at AT TIME ZONE " + TZ + ")::date FROM refresh_tokens rt "
                    + "JOIN users u ON u.id = rt.user_id AND u.role = 'CUSTOMER' "
                    + "WHERE rt.created_at >= :lo AND rt.created_at < :hi) t GROUP BY 1", r);
            case TOTAL_USERS -> cumulative(usersBefore(r.lo()), byBucket("SELECT "
                    + bucket.formatted("(created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, count(*) v FROM users WHERE role = 'CUSTOMER' AND created_at >= :lo AND created_at < :hi "
                    + "GROUP BY 1", r), r, g);
            case CUSTOMER_DEPOSITS -> cumulative(depositsBefore(r.lo()), byBucket("SELECT "
                    + bucket.formatted("(l.created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END) v "
                    + "FROM ledger_lines l JOIN accounts a ON a.id = l.ledger_account_id "
                    + "WHERE l.created_at >= :lo AND l.created_at < :hi GROUP BY 1", r), r, g);
            case NET_INFLOW -> flowSeries(bucket, "SUM(amt)", LedgerKinds.DEPOSITS, r);
            case TRANSFER_VOLUME -> flowSeries(bucket, "SUM(amt)", "'TRANSFER_OUT'", r);
            case TRANSFER_COUNT -> flowSeries(bucket, "SUM(sgn)", "'TRANSFER_OUT'", r);
            case TRADE_VOLUME -> flowSeries(bucket, "SUM(amt)", "'INVESTED','DIVESTED'", r);
            case TRADE_COUNT -> flowSeries(bucket, "SUM(sgn)", "'INVESTED','DIVESTED'", r);
            case LOAN_DISBURSED -> byBucket("SELECT " + bucket.formatted("(disbursed_at AT TIME ZONE " + TZ + ")::date")
                    + " b, SUM(amount) v FROM loans WHERE disbursed_at >= :lo AND disbursed_at < :hi GROUP BY 1", r);
            case LOAN_REPAID -> byBucket("SELECT " + bucket.formatted("(created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, SUM(amount) v FROM loan_repayments WHERE created_at >= :lo AND created_at < :hi "
                    + "GROUP BY 1", r);
            case INTEREST_INCOME -> byBucket("SELECT " + bucket.formatted("(created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, SUM(interest_paid) v FROM loan_repayments WHERE created_at >= :lo AND created_at < :hi "
                    + "GROUP BY 1", r);
            case FAILED_MONEY_REQUESTS -> byBucket("SELECT " + bucket.formatted("(created_at AT TIME ZONE " + TZ + ")::date")
                    + " b, count(*) v FROM request_failures WHERE created_at >= :lo AND created_at < :hi GROUP BY 1", r);
            case OVERDUE_AMOUNT -> overdueSeries(r, g);
            default -> Map.of();
        };
        List<SeriesPoint> points = new ArrayList<>();
        for (LocalDate b : buckets(r, g)) {
            points.add(new SeriesPoint(b, format(m.unit, data.getOrDefault(b, BigDecimal.ZERO))));
        }
        return points;
    }

    private Map<LocalDate, BigDecimal> flowSeries(String bucket, String agg, String kinds, AnalyticsRange r) {
        return byBucket("WITH " + LedgerKinds.cl(false) + " SELECT " + bucket.formatted("day") + " b, " + agg
                + " v FROM cl WHERE kind IN (" + kinds + ") GROUP BY 1", r);
    }

    /** Running total: the value before the range plus everything added up to the end of each bucket. */
    private Map<LocalDate, BigDecimal> cumulative(BigDecimal base, Map<LocalDate, BigDecimal> increments,
                                                  AnalyticsRange r, Granularity g) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        BigDecimal running = base;
        for (LocalDate b : buckets(r, g)) {
            running = running.add(increments.getOrDefault(b, BigDecimal.ZERO));
            out.put(b, running);
        }
        return out;
    }

    private Map<LocalDate, BigDecimal> overdueSeries(AnalyticsRange r, Granularity g) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        for (LocalDate b : buckets(r, g)) {
            LocalDate end = bucketEnd(b, g);
            if (end.isAfter(r.to())) {
                end = r.to();
            }
            out.put(b, overdueAsOf(end, end.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        }
        return out;
    }

    static List<LocalDate> buckets(AnalyticsRange r, Granularity g) {
        List<LocalDate> out = new ArrayList<>();
        LocalDate b = truncate(r.from(), g);
        while (!b.isAfter(r.to())) {
            out.add(b);
            b = switch (g) {
                case DAY -> b.plusDays(1);
                case WEEK -> b.plusWeeks(1);
                case MONTH -> b.plusMonths(1);
            };
        }
        return out;
    }

    static LocalDate truncate(LocalDate d, Granularity g) {
        return switch (g) {
            case DAY -> d;
            case WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> d.withDayOfMonth(1);
        };
    }

    private static LocalDate bucketEnd(LocalDate start, Granularity g) {
        return switch (g) {
            case DAY -> start;
            case WEEK -> start.plusDays(6);
            case MONTH -> start.with(TemporalAdjusters.lastDayOfMonth());
        };
    }

    // ------------------------------------------------------------------ breakdowns (F-ANL-3)

    private record Cell(String key, String label, BigDecimal value, long count, long users) {
    }

    @Transactional(readOnly = true)
    public BreakdownDto breakdown(Dimension dim, AnalyticsRange r, int limit) {
        int cap = Math.max(1, Math.min(limit, 50));
        List<Cell> cells = switch (dim) {
            case TRANSACTION_TYPE -> cells("""
                    SELECT e.type AS k, e.type AS label, COUNT(DISTINCT e.id) AS cnt,
                           COALESCE(SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE 0 END), 0) AS val,
                           COUNT(DISTINCT a.user_id) AS users
                      FROM journal_entries e JOIN ledger_lines l ON l.journal_entry_id = e.id
                      LEFT JOIN accounts a ON a.id = l.ledger_account_id
                     WHERE e.created_at >= :lo AND e.created_at < :hi GROUP BY e.type
                    """, r);
            case ACCOUNT_TYPE -> cells("""
                    SELECT a.type AS k, a.type AS label, COUNT(*) AS cnt, COALESCE(SUM(la.balance), 0) AS val,
                           COUNT(DISTINCT a.user_id) AS users
                      FROM accounts a JOIN ledger_accounts la ON la.id = a.id
                     WHERE a.created_at < :hi GROUP BY a.type
                    """, r);
            case ACCOUNT_STATUS -> cells("""
                    SELECT a.status AS k, a.status AS label, COUNT(*) AS cnt, COALESCE(SUM(la.balance), 0) AS val,
                           COUNT(DISTINCT a.user_id) AS users
                      FROM accounts a JOIN ledger_accounts la ON la.id = a.id
                     WHERE a.created_at < :hi GROUP BY a.status
                    """, r);
            case INSTRUMENT -> cells("""
                    SELECT o.symbol AS k, MAX(i.name) AS label, COUNT(*) AS cnt, COALESCE(SUM(o.total), 0) AS val,
                           COUNT(DISTINCT o.user_id) AS users
                      FROM orders o JOIN instruments i ON i.symbol = o.symbol
                     WHERE o.status = 'FILLED' AND o.created_at >= :lo AND o.created_at < :hi GROUP BY o.symbol
                    """, r);
            case SECTOR -> cells("""
                    SELECT i.sector AS k, i.sector AS label, COUNT(*) AS cnt, COALESCE(SUM(o.total), 0) AS val,
                           COUNT(DISTINCT o.user_id) AS users
                      FROM orders o JOIN instruments i ON i.symbol = o.symbol
                     WHERE o.status = 'FILLED' AND o.created_at >= :lo AND o.created_at < :hi GROUP BY i.sector
                    """, r);
            case ORDER_SIDE -> cells("""
                    SELECT o.side AS k, o.side AS label, COUNT(*) AS cnt, COALESCE(SUM(o.total), 0) AS val,
                           COUNT(DISTINCT o.user_id) AS users
                      FROM orders o
                     WHERE o.status = 'FILLED' AND o.created_at >= :lo AND o.created_at < :hi GROUP BY o.side
                    """, r);
            case LOAN_STATUS -> cells("""
                    SELECT status AS k, status AS label, COUNT(*) AS cnt, COALESCE(SUM(amount), 0) AS val,
                           COUNT(DISTINCT user_id) AS users
                      FROM loans WHERE created_at >= :lo AND created_at < :hi GROUP BY status
                    """, r);
            case LOAN_PURPOSE -> cells("""
                    SELECT lower(btrim(purpose)) AS k, initcap(lower(btrim(purpose))) AS label, COUNT(*) AS cnt,
                           COALESCE(SUM(amount), 0) AS val, COUNT(DISTINCT user_id) AS users
                      FROM loans WHERE created_at >= :lo AND created_at < :hi GROUP BY lower(btrim(purpose))
                    """, r);
            case FUNDING_SOURCE -> cells("""
                    SELECT CASE e.type WHEN 'TOPUP' THEN 'RAZORPAY' ELSE 'DEMO_DEPOSIT' END AS k,
                           CASE e.type WHEN 'TOPUP' THEN 'Razorpay top-up' ELSE 'Demo deposit' END AS label,
                           COUNT(DISTINCT l.id) AS cnt, COALESCE(SUM(l.amount), 0) AS val,
                           COUNT(DISTINCT a.user_id) AS users
                      FROM ledger_lines l JOIN accounts a ON a.id = l.ledger_account_id
                      JOIN journal_entries e ON e.id = l.journal_entry_id
                     WHERE e.type IN ('DEPOSIT', 'TOPUP') AND l.direction = 'CREDIT'
                       AND l.created_at >= :lo AND l.created_at < :hi GROUP BY e.type
                    """, r);
            case ERROR_CODE -> cells("""
                    SELECT code AS k, code AS label, COUNT(*) AS cnt, COUNT(*) AS val,
                           COUNT(DISTINCT user_id) AS users
                      FROM request_failures WHERE created_at >= :lo AND created_at < :hi GROUP BY code
                    """, r);
            case SPEND_CATEGORY -> cells("WITH " + LedgerKinds.cl(false) + ", " + CategoryService.catCte() + """
                    SELECT cat.category AS k, c.label, SUM(cat.sgn) AS cnt, SUM(cat.amt) AS val,
                           COUNT(DISTINCT cat.user_id) AS users
                      FROM cat JOIN categories c ON c.key = cat.category
                     WHERE cat.kind IN (%s) GROUP BY cat.category, c.label HAVING SUM(cat.amt) > 0
                    """.formatted(LedgerKinds.SPENDING), r);
        };
        Unit unit = switch (dim) {
            case ERROR_CODE -> Unit.COUNT;
            default -> Unit.MONEY;
        };
        return toBreakdown(dim, unit, cells, cap);
    }

    private BreakdownDto toBreakdown(Dimension dim, Unit unit, List<Cell> cells, int cap) {
        BigDecimal total = cells.stream().map(Cell::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Cell> top = cells.stream().sorted(Comparator.comparing(Cell::value).reversed()
                .thenComparing(Cell::key)).limit(cap).toList();
        List<BreakdownRow> rows = new ArrayList<>();
        int hidden = 0;
        for (Cell c : top) {
            if (c.users() < MIN_CELL_USERS) {
                // Neither the key nor the label may leak: free text such as a loan purpose can identify someone.
                rows.add(new BreakdownRow("SUPPRESSED_" + (++hidden), "Hidden (fewer than " + MIN_CELL_USERS
                        + " users)", null, null, null, true));
            } else {
                BigDecimal share = ratioPct(c.value(), total);
                rows.add(new BreakdownRow(c.key(), c.label(), format(unit, c.value()), c.count(),
                        share == null ? null : pct(share), false));
            }
        }
        return new BreakdownDto(dim.name(), unit, format(unit, total), rows);
    }

    private List<Cell> cells(String sql, AnalyticsRange r) {
        return jdbc.query(sql, params(r), (rs, i) -> new Cell(rs.getString("k"), rs.getString("label"),
                rs.getBigDecimal("val"), rs.getLong("cnt"), rs.getLong("users")));
    }

    // ------------------------------------------------------------------ funnel and cohorts (F-ANL-4/5)

    @Transactional(readOnly = true)
    public FunnelDto funnel(AnalyticsRange r) {
        long[] s = jdbc.queryForObject("""
                WITH u AS (SELECT id FROM users WHERE role = 'CUSTOMER' AND created_at >= :lo AND created_at < :hi),
                funded AS (SELECT DISTINCT a.user_id AS id FROM ledger_lines l
                             JOIN accounts a ON a.id = l.ledger_account_id
                             JOIN journal_entries e ON e.id = l.journal_entry_id
                            WHERE e.type IN ('DEPOSIT', 'TOPUP') AND l.direction = 'CREDIT' AND a.user_id IN (SELECT id FROM u)),
                xfer AS (SELECT DISTINCT user_id AS id FROM transfers
                          WHERE internal = false AND status = 'COMPLETED' AND user_id IN (SELECT id FROM u)),
                trade AS (SELECT DISTINCT user_id AS id FROM orders
                           WHERE status = 'FILLED' AND user_id IN (SELECT id FROM u)),
                loan AS (SELECT DISTINCT user_id AS id FROM loans WHERE user_id IN (SELECT id FROM u))
                SELECT (SELECT count(*) FROM u) AS s1,
                       (SELECT count(*) FROM funded) AS s2,
                       (SELECT count(*) FROM funded JOIN xfer USING (id)) AS s3,
                       (SELECT count(*) FROM funded JOIN xfer USING (id) JOIN trade USING (id)) AS s4,
                       (SELECT count(*) FROM funded JOIN xfer USING (id) JOIN trade USING (id) JOIN loan USING (id)) AS s5
                """, params(r), (rs, i) -> new long[]{rs.getLong("s1"), rs.getLong("s2"), rs.getLong("s3"),
                rs.getLong("s4"), rs.getLong("s5")});
        String[][] meta = {{"REGISTERED", "Registered"}, {"FUNDED", "Funded"}, {"FIRST_TRANSFER", "First transfer"},
                {"FIRST_TRADE", "First trade"}, {"LOAN_APPLIED", "Applied for a loan"}};
        List<FunnelStep> steps = new ArrayList<>();
        for (int i = 0; i < meta.length; i++) {
            BigDecimal users = BigDecimal.valueOf(s[i]);
            BigDecimal ofAll = ratioPct(users, BigDecimal.valueOf(s[0]));
            BigDecimal ofPrev = i == 0 ? ofAll : ratioPct(users, BigDecimal.valueOf(s[i - 1]));
            steps.add(new FunnelStep(meta[i][0], meta[i][1], count(users), pct(ofAll == null ? BigDecimal.ZERO : ofAll),
                    pct(ofPrev == null ? BigDecimal.ZERO : ofPrev)));
        }
        return new FunnelDto(steps);
    }

    @Transactional(readOnly = true)
    public CohortsDto cohorts(int months) {
        if (months < 1 || months > 12) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "months", "must be between 1 and 12");
        }
        LocalDate thisMonth = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).withDayOfMonth(1);
        LocalDate first = thisMonth.minusMonths(months - 1L);
        Instant lo = first.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        MapSqlParameterSource p = new MapSqlParameterSource("lo", Timestamp.from(lo));

        Map<LocalDate, Long> sizes = new TreeMap<>();
        jdbc.query("SELECT date_trunc('month', created_at AT TIME ZONE " + TZ + ")::date AS m0, count(*) AS n "
                        + "FROM users WHERE role = 'CUSTOMER' AND created_at >= :lo GROUP BY 1", p,
                rs -> {
                    sizes.put(rs.getDate("m0").toLocalDate(), rs.getLong("n"));
                });
        Map<LocalDate, Map<Integer, Long>> active = new HashMap<>();
        jdbc.query("""
                WITH cohort AS (SELECT id, date_trunc('month', created_at AT TIME ZONE 'Asia/Kolkata')::date AS m0
                                  FROM users WHERE role = 'CUSTOMER' AND created_at >= :lo),
                act AS (SELECT DISTINCT a.user_id, date_trunc('month', l.created_at AT TIME ZONE 'Asia/Kolkata')::date AS m
                          FROM ledger_lines l JOIN accounts a ON a.id = l.ledger_account_id WHERE l.created_at >= :lo)
                SELECT c.m0, act.m, count(DISTINCT c.id) AS n
                  FROM cohort c JOIN act ON act.user_id = c.id AND act.m >= c.m0 GROUP BY c.m0, act.m
                """, p, rs -> {
            LocalDate m0 = rs.getDate("m0").toLocalDate();
            int k = (int) Period.between(m0, rs.getDate("m").toLocalDate()).toTotalMonths();
            active.computeIfAbsent(m0, x -> new HashMap<>()).put(k, rs.getLong("n"));
        });

        List<CohortRow> rows = new ArrayList<>();
        for (int i = 0; i < months; i++) {
            LocalDate c = first.plusMonths(i);
            long size = sizes.getOrDefault(c, 0L);
            List<String> retention = new ArrayList<>();
            for (int k = 0; k < months; k++) {
                boolean future = c.plusMonths(k).isAfter(thisMonth);
                if (future || size < MIN_CELL_USERS) {
                    retention.add(null); // not yet happened, or a cell too small to show (F-ANL-10)
                } else {
                    long n = active.getOrDefault(c, Map.of()).getOrDefault(k, 0L);
                    retention.add(pct(ratioPct(BigDecimal.valueOf(n), BigDecimal.valueOf(size))));
                }
            }
            rows.add(new CohortRow(c.toString().substring(0, 7), String.valueOf(size), retention));
        }
        return new CohortsDto(rows);
    }

    // ------------------------------------------------------------------ loan book (F-ANL-6)

    @Transactional(readOnly = true)
    public LoanBookDto loanBook() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        Instant now = clock.instant();
        BigDecimal outstanding = outstandingBefore(now.plusSeconds(1));
        BigDecimal disbursed = num("SELECT COALESCE(SUM(amount), 0) FROM loans WHERE disbursed_at IS NOT NULL");
        BigDecimal repaid = num("SELECT COALESCE(SUM(amount), 0) FROM loan_repayments");
        BigDecimal interest = num("SELECT COALESCE(SUM(interest_paid), 0) FROM loan_repayments");
        BigDecimal active = num("SELECT count(*) FROM loans WHERE status = 'ACTIVE'");
        BigDecimal approved = num("SELECT count(*) FROM loans WHERE status IN ('APPROVED', 'ACTIVE', 'CLOSED')");
        BigDecimal rejected = num("SELECT count(*) FROM loans WHERE status = 'REJECTED'");
        BigDecimal approval = ratioPct(approved, approved.add(rejected));
        BigDecimal avg = num("SELECT COALESCE(AVG(amount), 0) FROM loans WHERE disbursed_at IS NOT NULL");
        BigDecimal overdue = overdueAsOf(today, now.plusSeconds(1));
        BigDecimal overdueRate = ratioPct(overdue, outstanding);

        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (String b : List.of("1-30", "31-60", "61-90", "90+")) {
            buckets.put(b, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
        }
        jdbc.query("""
                SELECT CASE WHEN :today - due_date <= 30 THEN '1-30' WHEN :today - due_date <= 60 THEN '31-60'
                            WHEN :today - due_date <= 90 THEN '61-90' ELSE '90+' END AS bucket,
                       count(*) AS n, COALESCE(SUM(emi - principal_paid - interest_paid), 0) AS amt
                  FROM loan_installments WHERE due_date < :today AND status <> 'PAID' GROUP BY 1
                """, new MapSqlParameterSource("today", java.sql.Date.valueOf(today)), rs -> {
            buckets.put(rs.getString("bucket"), new BigDecimal[]{rs.getBigDecimal("n"), rs.getBigDecimal("amt")});
        });
        List<AgeingBucket> ageing = buckets.entrySet().stream()
                .map(e -> new AgeingBucket(e.getKey(), count(e.getValue()[0]), money(e.getValue()[1]))).toList();
        List<StatusRow> byStatus = jdbc.getJdbcTemplate().query("""
                SELECT status, count(*) AS n, COALESCE(SUM(amount), 0) AS amt FROM loans GROUP BY status ORDER BY status
                """, (rs, i) -> new StatusRow(rs.getString("status"), count(rs.getBigDecimal("n")),
                money(rs.getBigDecimal("amt"))));
        return new LoanBookDto(money(outstanding), money(disbursed), money(repaid), money(interest), count(active),
                pct(approval == null ? BigDecimal.ZERO : approval), money(avg), money(overdue),
                pct(overdueRate == null ? BigDecimal.ZERO : overdueRate), ageing, byStatus);
    }

    // ------------------------------------------------------------------ adoption and health (F-ANL-7/8)

    @Transactional(readOnly = true)
    public AdoptionDto adoption(AnalyticsRange r) {
        BigDecimal active = value(Metric.ACTIVE_USERS, r, new Memo());
        String base = "WITH " + LedgerKinds.cl(false) + """
                , act AS (SELECT user_id FROM cl
                          UNION
                          SELECT rt.user_id FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id AND u.role = 'CUSTOMER'
                           WHERE rt.created_at >= :lo AND rt.created_at < :hi)
                SELECT count(*) FROM act WHERE
                """;
        BigDecimal passkey = scalar(base + " EXISTS (SELECT 1 FROM passkeys p WHERE p.user_id = act.user_id)", r);
        BigDecimal pin = scalar(base + " EXISTS (SELECT 1 FROM user_pins p WHERE p.user_id = act.user_id)", r);
        BigDecimal kyc = scalar(base + " EXISTS (SELECT 1 FROM kyc_profiles k WHERE k.user_id = act.user_id "
                + "AND k.status = 'VERIFIED')", r);
        List<FeatureAdoption> features = List.of(
                feature("HANDLE", "Payment handle", BigDecimal.ZERO, active, false),
                feature("PASSKEY", "Passkey", passkey, active, true),
                feature("PIN", "Transaction PIN", pin, active, true),
                feature("KYC", "KYC verified", kyc, active, true),
                feature("GOAL", "Savings goal", BigDecimal.ZERO, active, false),
                feature("AUTOPILOT_RULE", "Autopilot rule", BigDecimal.ZERO, active, false),
                feature("CARD", "Virtual card", BigDecimal.ZERO, active, false));
        return new AdoptionDto(features, new AiAdoption(false, "0", "0.00", "0.00"),
                new AutopilotAdoption(false, "0", "0.00"), new FraudAdoption(false, "0", "0.00"));
    }

    private static FeatureAdoption feature(String key, String label, BigDecimal users, BigDecimal active,
                                           boolean available) {
        BigDecimal pct = ratioPct(users, active);
        return new FeatureAdoption(key, label, count(users), pct(pct == null ? BigDecimal.ZERO : pct), available);
    }

    @Transactional(readOnly = true)
    public HealthDto health() {
        Health h = cachedHealth;
        long now = clock.millis();
        if (h == null || now - h.at() > HEALTH_TTL_MILLIS) {
            ReconcileReport report = ledger.reconcile();
            h = new Health(now, report.balanced());
            cachedHealth = h;
        }
        BigDecimal pending = num("SELECT count(*) FROM outbox_events WHERE processed_at IS NULL");
        BigDecimal failed = num("SELECT count(*) FROM request_failures WHERE created_at >= now() - interval '24 hours'");
        BigDecimal ok = num("SELECT count(DISTINCT e.id) FROM journal_entries e WHERE e.type <> 'REVERSAL' "
                + "AND e.created_at >= now() - interval '24 hours'");
        BigDecimal rate = ratioPct(failed, failed.add(ok));
        return new HealthDto(h.balanced(), Instant.ofEpochMilli(h.at()), null, count(pending),
                pct(rate == null ? BigDecimal.ZERO : rate));
    }

    // ------------------------------------------------------------------ queries

    private static MapSqlParameterSource params(AnalyticsRange r) {
        return new MapSqlParameterSource("lo", Timestamp.from(r.lo())).addValue("hi", Timestamp.from(r.hi()));
    }

    /** A single number from a query that uses :lo and :hi. */
    private BigDecimal scalar(String sql, AnalyticsRange r) {
        BigDecimal v = jdbc.queryForObject(sql, params(r), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal num(String sql) {
        BigDecimal v = jdbc.getJdbcTemplate().queryForObject(sql, BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private BigDecimal before(String sql, Instant hi) {
        BigDecimal v = jdbc.queryForObject(sql, new MapSqlParameterSource("hi", Timestamp.from(hi)), BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    private Map<LocalDate, BigDecimal> byBucket(String sql, AnalyticsRange r) {
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        jdbc.query(sql, params(r), rs -> {
            out.put(rs.getDate("b").toLocalDate(), rs.getBigDecimal("v"));
        });
        return out;
    }

    private BigDecimal usersBefore(Instant hi) {
        return before("SELECT count(*) FROM users WHERE role = 'CUSTOMER' AND created_at < :hi", hi);
    }

    private BigDecimal fundedBefore(Instant hi) {
        return before("""
                SELECT count(DISTINCT a.user_id) FROM ledger_lines l
                  JOIN accounts a ON a.id = l.ledger_account_id
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                 WHERE e.type IN ('DEPOSIT', 'TOPUP') AND l.direction = 'CREDIT' AND l.created_at < :hi
                """, hi);
    }

    /** Total customer liabilities as of {@code hi}: customer accounts are credit-normal. */
    private BigDecimal depositsBefore(Instant hi) {
        return before("""
                SELECT COALESCE(SUM(CASE l.direction WHEN 'CREDIT' THEN l.amount ELSE -l.amount END), 0)
                  FROM ledger_lines l JOIN accounts a ON a.id = l.ledger_account_id WHERE l.created_at < :hi
                """, hi);
    }

    private BigDecimal outstandingBefore(Instant hi) {
        return before("""
                SELECT COALESCE((SELECT SUM(amount) FROM loans WHERE disbursed_at < :hi), 0)
                     - COALESCE((SELECT SUM(principal_paid) FROM loan_repayments WHERE created_at < :hi), 0)
                """, hi);
    }

    /**
     * Installments that were past due on {@code day} and still unpaid at the end of it ({@code endOfDay}). A
     * paid installment counts in full if it was paid after that moment; an unpaid one counts what is still owed.
     */
    private BigDecimal overdueAsOf(LocalDate day, Instant endOfDay) {
        BigDecimal v = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN paid_at IS NULL THEN emi - principal_paid - interest_paid ELSE emi END), 0)
                  FROM loan_installments
                 WHERE due_date < :day AND (paid_at IS NULL OR paid_at >= :end)
                """, new MapSqlParameterSource("day", java.sql.Date.valueOf(day)).addValue("end", Timestamp.from(endOfDay)),
                BigDecimal.class);
        return v == null ? BigDecimal.ZERO : v;
    }

    /** {volume, count} for one ledger-line kind in a range (signed, so reversals cancel). */
    private BigDecimal[] flow(AnalyticsRange r, Memo memo, String kind) {
        Map<String, BigDecimal[]> byKind = memo.flows.computeIfAbsent(r, range -> {
            Map<String, BigDecimal[]> out = new HashMap<>();
            jdbc.query("WITH " + LedgerKinds.cl(false) + " SELECT kind, COALESCE(SUM(amt), 0) AS vol, "
                    + "COALESCE(SUM(sgn), 0) AS cnt FROM cl GROUP BY kind", params(range), rs -> {
                out.put(rs.getString("kind"), new BigDecimal[]{rs.getBigDecimal("vol"), rs.getBigDecimal("cnt")});
            });
            return out;
        });
        return byKind.getOrDefault(kind, new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
    }
}
