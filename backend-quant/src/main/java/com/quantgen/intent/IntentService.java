package com.quantgen.intent;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.security.UserRateLimiter;
import com.quantgen.intent.IntentKinds.Prepared;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-AGT-2: intents. A proposal is validated and its approval card ({@code summary}) is rendered here from the
 * validated payload, never by a model (D-023). Nothing moves until the user approves, with step-up when the intent
 * says so; approval re-validates and executes through the normal services (limits, holds, frozen checks, fraud
 * rules). A business refusal at that point is a 200 with status FAILED and a failure code, not an error.
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    static final Duration TTL = Duration.ofMinutes(15);
    static final UserRateLimiter.Limit PROPOSE_LIMIT = new UserRateLimiter.Limit("intent-propose", 30, Duration.ofMinutes(1));
    public static final List<String> AGENTS = List.of("CONCIERGE", "LEDGER_ANALYST", "PAYMENTS", "MARKETS", "PLANNER",
            "LOAN_ADVISOR", "MCP_CLIENT");
    /** Codes that mean "the bank said no" at execution; anything else is a real error. */
    static final List<ErrorCode> BUSINESS_FAILURES = List.of(ErrorCode.INSUFFICIENT_FUNDS, ErrorCode.INSUFFICIENT_HOLDINGS,
            ErrorCode.LIMIT_EXCEEDED, ErrorCode.ACCOUNT_FROZEN, ErrorCode.COOLING_PERIOD, ErrorCode.INVALID_STATE,
            ErrorCode.NOT_FOUND, ErrorCode.VALIDATION_FAILED, ErrorCode.KYC_REQUIRED, ErrorCode.FEATURE_DISABLED);

    public record Line(String label, String value) {
    }

    public record Summary(String title, List<Line> lines) {
    }

    public record ProposedBy(String agent) {
    }

    public record ResultRef(String type, UUID id) {
    }

    public record IntentDto(UUID id, String kind, String status, Summary summary, String rationale, String riskLevel,
                            boolean requiresStepUp, ProposedBy proposedBy, UUID conversationId, String failureCode,
                            ResultRef resultRef, Instant expiresAt, Instant createdAt) {
    }

    private record Row(IntentDto dto, JsonNode payload) {
    }

    private final JdbcTemplate jdbc;
    private final IntentKinds kinds;
    private final IdempotencyService idempotency;
    private final StepUpService stepUp;
    private final AuditService audit;
    private final UserRateLimiter rateLimiter;
    private final JsonMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    IntentService(JdbcTemplate jdbc, IntentKinds kinds, IdempotencyService idempotency, StepUpService stepUp,
                  AuditService audit, UserRateLimiter rateLimiter, JsonMapper json, Clock clock,
                  PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.kinds = kinds;
        this.idempotency = idempotency;
        this.stepUp = stepUp;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ propose

    public IntentDto propose(UUID userId, UUID tokenId, String agent, String kind, JsonNode payload, UUID conversationId,
                             String rationale) {
        rateLimiter.acquire(PROPOSE_LIMIT, userId);
        if (!AGENTS.contains(agent)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "agent", "must be one of " + String.join(", ", AGENTS));
        }
        if (payload == null || !payload.isObject()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "payload", "must be an object");
        }
        return tx.execute(st -> {
            Prepared p = kinds.prepare(userId, kind, payload);
            Instant now = clock.instant();
            UUID id = jdbc.queryForObject("""
                    INSERT INTO intents (user_id, kind, payload, summary, rationale, risk_level, requires_step_up,
                                         proposed_by, conversation_id, source_token_id, expires_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, userId, kind, json.writeValueAsString(p.payload()),
                    json.writeValueAsString(p.summary()), rationale, p.riskLevel(), p.requiresStepUp(), agent,
                    conversationId, tokenId, Timestamp.from(now.plus(TTL)), Timestamp.from(now));
            audit.record(userId, "INTENT_PROPOSED", "INTENT", id.toString(), kind + " by " + agent
                    + (tokenId == null ? "" : " (token " + tokenId + ")"));
            return find(userId, id, false).dto();
        });
    }

    // ------------------------------------------------------------------ read

    public List<IntentDto> list(UUID userId, String status) {
        if (status != null && !List.of("PROPOSED", "EXECUTED", "REJECTED", "EXPIRED", "FAILED").contains(status)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "unknown status");
        }
        // An open intent past its expiry reads as EXPIRED even before the sweep has run.
        String filter = status == null ? "" : " AND (CASE WHEN status = 'PROPOSED' AND expires_at <= ? THEN 'EXPIRED' "
                + "ELSE status END) = ?";
        List<Object> args = new ArrayList<>(List.of(userId));
        if (status != null) {
            args.add(Timestamp.from(clock.instant()));
            args.add(status);
        }
        return jdbc.query(SELECT + " WHERE user_id = ?" + filter + " ORDER BY created_at DESC, id LIMIT 100",
                (rs, i) -> row(rs).dto(), args.toArray());
    }

    // ------------------------------------------------------------------ decide

    public IdempotencyService.Result<IntentDto> approve(UUID userId, UUID key, String fingerprint, UUID intentId,
                                                       String stepUpToken) {
        try {
            return tx.execute(st -> idempotency.execute(userId, key, fingerprint, 200, IntentDto.class, () -> {
                Row row = openIntent(userId, intentId);
                if (row.dto().requiresStepUp()) {
                    stepUp.require(userId, StepUpAction.INTENT_APPROVE, stepUpToken);
                }
                ResultRef result = kinds.execute(userId, intentId, row.dto().kind(), row.payload());
                jdbc.update("UPDATE intents SET status = 'EXECUTED', result_type = ?, result_id = ?, decided_at = ? "
                        + "WHERE id = ?", result.type(), result.id(), Timestamp.from(clock.instant()), intentId);
                audit.record(userId, "INTENT_EXECUTED", "INTENT", intentId.toString(),
                        row.dto().kind() + " → " + result.type() + " " + result.id());
                return find(userId, intentId, false).dto();
            }));
        } catch (ApiException e) {
            if (!BUSINESS_FAILURES.contains(e.code())) {
                throw e; // 401 step-up, 404 unknown intent, 409 expired/decided, 429 … stay errors
            }
            // The execution was rolled back; record the refusal (and the idempotent answer) in a new transaction.
            return tx.execute(st -> idempotency.execute(userId, key, fingerprint, 200, IntentDto.class, () -> {
                openIntent(userId, intentId);
                jdbc.update("UPDATE intents SET status = 'FAILED', failure_code = ?, decided_at = ? WHERE id = ?",
                        e.code().name(), Timestamp.from(clock.instant()), intentId);
                audit.record(userId, "INTENT_FAILED", "INTENT", intentId.toString(), e.code().name());
                return find(userId, intentId, false).dto();
            }));
        }
    }

    public IntentDto reject(UUID userId, UUID intentId) {
        return tx.execute(st -> {
            openIntent(userId, intentId);
            jdbc.update("UPDATE intents SET status = 'REJECTED', decided_at = ? WHERE id = ?",
                    Timestamp.from(clock.instant()), intentId);
            audit.record(userId, "INTENT_REJECTED", "INTENT", intentId.toString(), null);
            return find(userId, intentId, false).dto();
        });
    }

    /** The user's own PROPOSED, unexpired intent, row-locked; someone else's is 404. */
    private Row openIntent(UUID userId, UUID intentId) {
        Row row = find(userId, intentId, true);
        if ("EXPIRED".equals(row.dto().status())) {
            throw new ApiException(ErrorCode.INTENT_EXPIRED, "This intent has expired; ask again");
        }
        if (!"PROPOSED".equals(row.dto().status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This intent has already been decided");
        }
        return row;
    }

    @Scheduled(fixedDelayString = "${qg.intents.expiry-sweep-millis:60000}")
    void expire() {
        try {
            int n = jdbc.update("UPDATE intents SET status = 'EXPIRED', decided_at = expires_at "
                    + "WHERE status = 'PROPOSED' AND expires_at <= ?", Timestamp.from(clock.instant()));
            if (n > 0) {
                log.info("Expired {} intent(s)", n);
            }
        } catch (RuntimeException e) {
            log.error("Intent expiry sweep failed", e);
        }
    }

    // ------------------------------------------------------------------ rows

    private static final String SELECT = """
            SELECT id, kind, status, summary, payload, rationale, risk_level, requires_step_up, proposed_by,
                   conversation_id, failure_code, result_type, result_id, expires_at, created_at FROM intents
            """;

    private Row find(UUID userId, UUID intentId, boolean lock) {
        return jdbc.query(SELECT + " WHERE id = ? AND user_id = ?" + (lock ? " FOR UPDATE" : ""), (rs, i) -> row(rs),
                intentId, userId).stream().findFirst().orElseThrow(() -> ApiException.notFound("Intent"));
    }

    private Row row(ResultSet rs) throws SQLException {
        Instant expiresAt = rs.getTimestamp("expires_at").toInstant();
        String status = rs.getString("status");
        if ("PROPOSED".equals(status) && !clock.instant().isBefore(expiresAt)) {
            status = "EXPIRED";
        }
        UUID resultId = rs.getObject("result_id", UUID.class);
        IntentDto dto = new IntentDto(rs.getObject("id", UUID.class), rs.getString("kind"), status,
                json.readValue(rs.getString("summary"), Summary.class), rs.getString("rationale"),
                rs.getString("risk_level"), rs.getBoolean("requires_step_up"), new ProposedBy(rs.getString("proposed_by")),
                rs.getObject("conversation_id", UUID.class), rs.getString("failure_code"),
                resultId == null ? null : new ResultRef(rs.getString("result_type"), resultId), expiresAt,
                rs.getTimestamp("created_at").toInstant());
        return new Row(dto, json.readTree(rs.getString("payload")));
    }
}
