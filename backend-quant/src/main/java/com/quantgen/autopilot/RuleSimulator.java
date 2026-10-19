package com.quantgen.autopilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.autopilot.RuleDsl.Action;
import com.quantgen.autopilot.RuleDsl.Condition;
import com.quantgen.autopilot.RuleDsl.Trigger;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Inr;

/**
 * F-AUTO-3 replay: dry-runs a rule against the user's real ledger history for the last N days (90 by default) with a
 * fake balance tracker, using the same trigger matching, conditions, amounts and caps as the engine. It never calls a
 * posting service. Buys are priced at today's price.
 */
@Component
class RuleSimulator {

    static final int MAX_FIRINGS = 200;
    private static final LocalTime RUN_TIME = LocalTime.of(8, 0);

    public record ActionResult(String summary, BigDecimal amount, boolean wouldSucceed, String reason) {
    }

    public record Firing(LocalDate date, String triggerSummary, List<ActionResult> actions) {
    }

    public record SimulationDto(List<Firing> firings, BigDecimal totalMoved, int failures) {
    }

    private record Event(Instant at, String summary, BigDecimal amount) {
    }

    private final JdbcTemplate jdbc;
    private final RuleRenderer renderer;
    private final Clock clock;

    RuleSimulator(JdbcTemplate jdbc, RuleRenderer renderer, Clock clock) {
        this.jdbc = jdbc;
        this.renderer = renderer;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SimulationDto simulate(RuleDsl rule, int days) {
        if (days < 1 || days > 180) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "days", "must be 1–180");
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate first = today.minusDays(days);
        List<Event> events = events(rule.trigger(), first, today);

        Map<UUID, BigDecimal> delta = new HashMap<>(); // simulated movements per account
        Map<String, BigDecimal> monthUsed = new HashMap<>();
        Map<LocalDate, BigDecimal> dayUsed = new HashMap<>();
        List<Firing> firings = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int failures = 0;
        for (Event e : events) {
            LocalDate date = LocalDate.ofInstant(e.at(), TimeConfig.BUSINESS_ZONE);
            boolean conditionsHold = true;
            for (Condition c : rule.conditions()) {
                if (balance(c.accountId(), e.at(), delta).compareTo(c.amount()) < 0) {
                    conditionsHold = false;
                }
            }
            if (!conditionsHold) {
                continue;
            }
            List<BigDecimal> amounts = new ArrayList<>();
            BigDecimal planned = BigDecimal.ZERO;
            for (Action a : rule.actions()) {
                BigDecimal amt = amount(a, e, delta);
                amounts.add(amt);
                planned = planned.add(amt);
            }
            String month = date.toString().substring(0, 7);
            String capReason = planned.compareTo(rule.caps().perRun()) > 0 ? "Over the per-run cap"
                    : monthUsed.getOrDefault(month, BigDecimal.ZERO).add(planned).compareTo(rule.caps().perMonth()) > 0
                    ? "Over the monthly cap"
                    : dayUsed.getOrDefault(date, BigDecimal.ZERO).add(planned).compareTo(AutopilotEngine.DAILY_CAP) > 0
                    ? "Over Autopilot's ₹1,00,000 daily limit" : null;
            List<ActionResult> results = new ArrayList<>();
            boolean stopped = capReason != null;
            BigDecimal runMoved = BigDecimal.ZERO;
            for (int i = 0; i < rule.actions().size(); i++) {
                Action a = rule.actions().get(i);
                BigDecimal amt = amounts.get(i);
                String summary = capitalize(renderer.action(a));
                if (stopped) {
                    results.add(new ActionResult(summary, amt, false, capReason != null ? capReason : "Skipped after an earlier failure"));
                    failures++;
                    continue;
                }
                String reason = null;
                switch (a.type()) {
                    case "TRANSFER" -> {
                        if (balance(a.fromAccountId(), e.at(), delta).compareTo(amt) < 0) {
                            reason = "Not enough money in " + renderer.account(a.fromAccountId()) + " that day";
                        } else if (amt.signum() > 0) {
                            delta.merge(a.fromAccountId(), amt.negate(), BigDecimal::add);
                            if (a.toAccountId() != null) {
                                delta.merge(a.toAccountId(), amt, BigDecimal::add);
                            }
                        }
                    }
                    case "BUY" -> {
                        BigDecimal price = jdbc.queryForObject("SELECT price FROM instruments WHERE symbol = ?",
                                BigDecimal.class, a.symbol());
                        long qty = amt.divide(price, 0, RoundingMode.DOWN).longValue();
                        BigDecimal cost = price.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_EVEN);
                        if (qty < 1) {
                            reason = "Less than one share at today's price (" + Inr.format(price) + ")";
                        } else if (balance(a.accountId(), e.at(), delta).compareTo(cost) < 0) {
                            reason = "Not enough money in " + renderer.account(a.accountId()) + " that day";
                        } else {
                            delta.merge(a.accountId(), cost.negate(), BigDecimal::add);
                            amt = cost;
                        }
                    }
                    default -> {
                        // NOTIFY always succeeds and moves nothing
                    }
                }
                if (reason != null) {
                    results.add(new ActionResult(summary, amt, false, reason));
                    failures++;
                    stopped = true; // like the engine: a failed action stops the run
                } else {
                    results.add(new ActionResult(summary, amt, true, null));
                    if (!"NOTIFY".equals(a.type())) {
                        runMoved = runMoved.add(amt);
                    }
                }
            }
            monthUsed.merge(month, runMoved, BigDecimal::add);
            dayUsed.merge(date, runMoved, BigDecimal::add);
            total = total.add(runMoved);
            firings.add(new Firing(date, e.summary(), results));
        }
        List<Firing> newestFirst = new ArrayList<>(firings.reversed());
        return new SimulationDto(newestFirst.size() > MAX_FIRINGS ? newestFirst.subList(0, MAX_FIRINGS) : newestFirst,
                total, failures);
    }

    // ------------------------------------------------------------------ history

    private List<Event> events(Trigger t, LocalDate first, LocalDate today) {
        List<Event> out = new ArrayList<>();
        switch (t.type()) {
            case "MONEY_RECEIVED" -> {
                Instant since = first.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
                jdbc.query("""
                        SELECT l.amount, l.counterparty, l.created_at, e.type FROM ledger_lines l
                          JOIN journal_entries e ON e.id = l.journal_entry_id
                         WHERE l.ledger_account_id = ? AND l.direction = 'CREDIT' AND l.created_at >= ?
                           AND e.type <> 'REVERSAL'
                           AND NOT EXISTS (SELECT 1 FROM transfers x WHERE x.journal_entry_id = l.journal_entry_id
                                            AND x.source = 'AUTOPILOT')
                         ORDER BY l.created_at, l.id
                        """, rs -> {
                    BigDecimal amount = rs.getBigDecimal("amount");
                    String cp = rs.getString("counterparty");
                    if ((t.minAmount() != null && amount.compareTo(t.minAmount()) < 0)
                            || (t.transactionType() != null && !t.transactionType().equals(rs.getString("type")))
                            || (t.counterpartyContains() != null && (cp == null
                            || !cp.toLowerCase().contains(t.counterpartyContains().toLowerCase())))) {
                        return;
                    }
                    out.add(new Event(rs.getTimestamp("created_at").toInstant(), Inr.format(amount)
                            + (cp == null ? "" : " " + cp) + " into " + renderer.account(t.accountId()), amount));
                }, t.accountId(), Timestamp.from(since));
            }
            case "SCHEDULE" -> {
                for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
                    boolean due = "MONTHLY".equals(t.frequency()) ? d.getDayOfMonth() == t.dayOfMonth()
                            : d.getDayOfWeek() == DayOfWeek.of(RuleValidator.WEEKDAYS.indexOf(t.weekday()) + 1);
                    if (due) {
                        out.add(new Event(at(d), "MONTHLY".equals(t.frequency()) ? "Scheduled: "
                                + RuleRenderer.ordinal(t.dayOfMonth()) + " of the month" : "Scheduled: every "
                                + t.weekday().charAt(0) + t.weekday().substring(1).toLowerCase(), null));
                    }
                }
            }
            case "BALANCE_ABOVE" -> {
                for (LocalDate d = first; !d.isAfter(today); d = d.plusDays(1)) {
                    BigDecimal held = balance(t.accountId(), at(d), Map.of());
                    if (held.compareTo(t.threshold()) > 0) {
                        out.add(new Event(at(d), "Daily check: " + renderer.account(t.accountId()) + " held "
                                + Inr.format(held), held));
                    }
                }
            }
            default -> jdbc.query("""
                    SELECT (ts AT TIME ZONE 'Asia/Kolkata')::date AS day, min(price) AS lo, max(price) AS hi
                      FROM price_ticks WHERE symbol = ? AND ts >= ? GROUP BY 1 ORDER BY 1
                    """, rs -> {
                LocalDate d = rs.getObject("day", LocalDate.class);
                boolean above = "ABOVE".equals(t.direction());
                BigDecimal px = above ? rs.getBigDecimal("hi") : rs.getBigDecimal("lo");
                if (above ? px.compareTo(t.price()) > 0 : px.compareTo(t.price()) < 0) {
                    out.add(new Event(at(d), t.symbol() + " " + (above ? "rose to " : "fell to ") + Inr.format(px), px));
                }
            }, t.symbol(), Timestamp.from(first.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        }
        out.sort(Comparator.comparing(Event::at));
        return out;
    }

    /** The account's balance at {@code at} from its ledger lines, plus the simulated movements so far. */
    private BigDecimal balance(UUID accountId, Instant at, Map<UUID, BigDecimal> delta) {
        List<BigDecimal> last = jdbc.queryForList("SELECT balance_after FROM ledger_lines WHERE ledger_account_id = ? "
                + "AND created_at <= ? ORDER BY created_at DESC, id DESC LIMIT 1", BigDecimal.class, accountId, Timestamp.from(at));
        BigDecimal base = last.isEmpty() ? BigDecimal.ZERO : last.getFirst();
        return base.add(delta.getOrDefault(accountId, BigDecimal.ZERO));
    }

    private BigDecimal amount(Action a, Event e, Map<UUID, BigDecimal> delta) {
        if (a.amount() == null) {
            return BigDecimal.ZERO;
        }
        return switch (a.amount().mode()) {
            case "FIXED" -> a.amount().value();
            case "PERCENT_OF_TRIGGER" -> e.amount() == null ? BigDecimal.ZERO
                    : e.amount().multiply(a.amount().value()).divide(BigDecimal.valueOf(100), 2, RoundingMode.DOWN);
            default -> {
                UUID source = a.fromAccountId() != null ? a.fromAccountId() : a.accountId();
                yield balance(source, e.at(), delta).subtract(a.amount().value()).max(BigDecimal.ZERO)
                        .setScale(2, RoundingMode.DOWN);
            }
        };
    }

    private static Instant at(LocalDate d) {
        return d.atTime(RUN_TIME).atZone(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
