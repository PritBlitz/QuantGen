package com.quantgen.risk;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.events.OutboxService;
import com.quantgen.common.money.Money;

import tools.jackson.databind.json.JsonMapper;

/**
 * F-RISK-1 / D-030: deterministic fraud rules, run synchronously inside the payment transaction (indexed queries
 * over the payer's own recent history, no network). The score is the sum of the signals' weights, capped at 100;
 * at or above {@code qg.risk.review-threshold} a transfer waits for an admin and a card authorization is declined.
 * The LLM never decides: the Fraud Investigator agent (V2-M4) only explains a case afterwards.
 */
@Service
public class RiskService {

    public static final String CASE_OPENED_EVENT = "TRANSFER_REVIEW_OPENED";
    static final Duration VELOCITY_WINDOW = Duration.ofMinutes(10);
    static final Duration ROUND_WINDOW = Duration.ofHours(1);
    static final Duration SECURITY_CHANGE_WINDOW = Duration.ofHours(24);
    static final BigDecimal LARGE = new BigDecimal("20000.00");
    static final BigDecimal NOTABLE = new BigDecimal("10000.00");
    static final BigDecimal ROUND_UNIT = new BigDecimal("1000");
    static final int BASELINE_MULTIPLE = 5;

    public record Signal(String code, String label, int weight) {
    }

    public record Assessment(int score, List<Signal> signals) {

        /** The label of the heaviest signal (the case list shows it). */
        public String topSignal() {
            return signals.stream().max(Comparator.comparingInt(Signal::weight)).map(Signal::label).orElse("");
        }
    }

    private static final Assessment NONE = new Assessment(0, List.of());

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final JsonMapper json;
    private final AuditService audit;
    private final OutboxService outbox;
    private final boolean enabled;
    private final int threshold;

    RiskService(JdbcTemplate jdbc, Clock clock, JsonMapper json, AuditService audit, OutboxService outbox,
                @Value("${qg.risk.enabled:true}") boolean enabled,
                @Value("${qg.risk.review-threshold:60}") int threshold) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.json = json;
        this.audit = audit;
        this.outbox = outbox;
        this.enabled = enabled;
        this.threshold = threshold;
    }

    public boolean needsReview(Assessment a) {
        return enabled && a.score() >= threshold;
    }

    // ------------------------------------------------------------------ transfers

    /** Scores an outgoing transfer to another person. Call after the payer's transfer lock is held. */
    public Assessment scoreTransfer(UUID userId, UUID toAccountId, BigDecimal amount) {
        if (!enabled) {
            return NONE;
        }
        Instant now = clock.instant();
        List<Signal> signals = new ArrayList<>();

        Long recent = jdbc.queryForObject("""
                SELECT count(*) FROM transfers WHERE user_id = ? AND internal = FALSE
                   AND status IN ('COMPLETED', 'PENDING_REVIEW') AND created_at >= ?
                """, Long.class, userId, ts(now.minus(VELOCITY_WINDOW)));
        if (recent != null && recent + 1 >= 5) {
            signals.add(new Signal("VELOCITY", (recent + 1) + " transfers in 10 minutes", 25));
        }

        Boolean paidBefore = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transfers WHERE user_id = ? AND to_account_id = ? AND status = 'COMPLETED')
                """, Boolean.class, userId, toAccountId);
        if (!Boolean.TRUE.equals(paidBefore) && amount.compareTo(LARGE) >= 0) {
            signals.add(new Signal("NEW_PAYEE_LARGE", "Large amount to someone never paid before", 35));
        }

        Instant change = lastSecurityChange(userId, now);
        if (change != null) {
            Boolean sentSince = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM transfers WHERE user_id = ? AND internal = FALSE AND created_at > ?)
                    """, Boolean.class, userId, ts(change));
            if (!Boolean.TRUE.equals(sentSince)) {
                signals.add(new Signal("DEVICE_CHANGE", "First transfer after a new-device sign-in or security change", 20));
            }
        }

        oddHour(now, amount, signals);

        if (isRound(amount)) {
            Long rounds = jdbc.queryForObject("""
                    SELECT count(*) FROM transfers WHERE user_id = ? AND internal = FALSE
                       AND status IN ('COMPLETED', 'PENDING_REVIEW') AND created_at >= ? AND mod(amount, 1000) = 0
                    """, Long.class, userId, ts(now.minus(ROUND_WINDOW)));
            if (rounds != null && rounds + 1 >= 3) {
                signals.add(new Signal("ROUND_AMOUNTS", (rounds + 1) + " round-amount transfers within an hour", 15));
            }
        }

        Map<String, Object> base = jdbc.queryForMap("""
                SELECT count(*) AS n, COALESCE(avg(amount), 0) AS avg FROM transfers
                 WHERE user_id = ? AND internal = FALSE AND status = 'COMPLETED' AND created_at >= ?
                """, userId, ts(now.minus(Duration.ofDays(90))));
        unusual(((Number) base.get("n")).longValue(), (BigDecimal) base.get("avg"), amount, "transfer", signals);

        return assess(signals);
    }

    /** Opens the review case of a held transfer: audited, and announced for the Fraud Investigator agent. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openCase(UUID transferId, UUID userId, UUID holdId, Assessment a) {
        UUID id = jdbc.queryForObject("""
                INSERT INTO fraud_cases (transfer_id, user_id, hold_id, score, signals) VALUES (?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, transferId, userId, holdId, a.score(), json.writeValueAsString(a.signals()));
        audit.record(userId, "FRAUD_CASE_OPENED", "FRAUD_CASE", id.toString(),
                "score=" + a.score() + " signals=" + a.signals().stream().map(Signal::code).toList());
        outbox.append(CASE_OPENED_EVENT, id, Map.of("caseId", id.toString(), "transferId", transferId.toString(),
                "score", a.score()));
        return id;
    }

    // ------------------------------------------------------------------ card authorizations

    /** Scores a sandbox card authorization (F-RISK-1); a held score declines it as "Suspected fraud". */
    public Assessment scoreCard(UUID cardId, BigDecimal amount) {
        if (!enabled) {
            return NONE;
        }
        Instant now = clock.instant();
        List<Signal> signals = new ArrayList<>();
        Long recent = jdbc.queryForObject("SELECT count(*) FROM card_authorizations WHERE card_id = ? AND created_at >= ?",
                Long.class, cardId, ts(now.minus(VELOCITY_WINDOW)));
        if (recent != null && recent + 1 >= 5) {
            signals.add(new Signal("VELOCITY", (recent + 1) + " card attempts in 10 minutes", 30));
        }
        Map<String, Object> base = jdbc.queryForMap("""
                SELECT count(*) AS n, COALESCE(avg(amount), 0) AS avg FROM card_authorizations
                 WHERE card_id = ? AND status <> 'DECLINED' AND created_at >= ?
                """, cardId, ts(now.minus(Duration.ofDays(90))));
        long n = ((Number) base.get("n")).longValue();
        if (n == 0 && amount.compareTo(LARGE) >= 0) {
            signals.add(new Signal("FIRST_USE_LARGE", "Large first payment with this card", 35));
        }
        oddHour(now, amount, signals);
        unusual(n, (BigDecimal) base.get("avg"), amount, "card payment", signals);
        return assess(signals);
    }

    // ------------------------------------------------------------------ rules shared by both

    private void oddHour(Instant now, BigDecimal amount, List<Signal> signals) {
        int hour = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).getHour();
        if (hour < 5 && amount.compareTo(NOTABLE) >= 0) {
            signals.add(new Signal("ODD_HOUR", "Between 00:00 and 05:00 IST", 20));
        }
    }

    private static void unusual(long count, BigDecimal avg, BigDecimal amount, String what, List<Signal> signals) {
        if (count >= 3 && avg.signum() > 0 && amount.compareTo(NOTABLE) >= 0
                && amount.compareTo(avg.multiply(BigDecimal.valueOf(BASELINE_MULTIPLE))) >= 0) {
            BigDecimal times = amount.divide(avg, 0, RoundingMode.DOWN);
            signals.add(new Signal("UNUSUAL_AMOUNT", times + "× the usual " + what + " (₹" + Money.toJson(
                    Money.roundToPaise(avg)) + ")", 25));
        }
    }

    private Instant lastSecurityChange(UUID userId, Instant now) {
        Timestamp since = ts(now.minus(SECURITY_CHANGE_WINDOW));
        Timestamp t = jdbc.queryForObject("""
                SELECT max(at) FROM (
                    SELECT max(created_at) AS at FROM notifications
                     WHERE user_id = ? AND type = 'NEW_DEVICE_LOGIN' AND created_at >= ?
                    UNION ALL
                    SELECT max(created_at) FROM audit_logs
                     WHERE actor_user_id = ? AND action IN ('PIN_SET', 'PASSKEY_ADDED', 'PASSKEY_REMOVED', 'SESSIONS_REVOKED_OTHERS')
                       AND created_at >= ?) changes
                """, Timestamp.class, userId, since, userId, since);
        return t == null ? null : t.toInstant();
    }

    private static boolean isRound(BigDecimal amount) {
        return amount.compareTo(ROUND_UNIT) >= 0 && amount.remainder(ROUND_UNIT).signum() == 0;
    }

    private static Assessment assess(List<Signal> signals) {
        return new Assessment(Math.min(100, signals.stream().mapToInt(Signal::weight).sum()), List.copyOf(signals));
    }

    private static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
