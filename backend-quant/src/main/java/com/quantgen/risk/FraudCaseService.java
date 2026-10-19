package com.quantgen.risk;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.payment.service.TransferService;
import com.quantgen.risk.RiskService.Signal;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** F-RISK-2: the admin fraud-case queue. Release posts the held transfer; reject frees the money. Both audited. */
@Service
public class FraudCaseService {

    public record Customer(UUID userId, String fullName) {
    }

    public record FraudCaseDto(UUID id, String status, int score, UUID transferId, BigDecimal amount,
                               String fromAccountNumberMasked, String toAccountNumberMasked, String toName,
                               Customer customer, String topSignal, Instant createdAt, Instant decidedAt,
                               String decisionReason) {
    }

    public record Baseline(BigDecimal avgTransfer, long transfers30d, String usualHoursIst, long accountAgeDays) {
    }

    public record FraudCaseDetailDto(UUID id, String status, int score, UUID transferId, BigDecimal amount,
                                     String fromAccountNumberMasked, String toAccountNumberMasked, String toName,
                                     Customer customer, String topSignal, Instant createdAt, Instant decidedAt,
                                     String decisionReason, JsonNode memo, List<Signal> signals,
                                     Baseline customerBaseline) {
    }

    private record CaseRow(FraudCaseDto dto, UUID holdId, List<Signal> signals, String memo) {
    }

    private static final List<String> STATUSES = List.of("OPEN", "RELEASED", "REJECTED");
    private static final TypeReference<List<Signal>> SIGNALS = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final TransferService transfers;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JsonMapper json;
    private final Clock clock;

    FraudCaseService(JdbcTemplate jdbc, TransferService transfers, LedgerService ledger, IdempotencyService idempotency,
                     NotificationService notifications, AuditService audit, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageDto<FraudCaseDto> list(String status, Pageable pageable) {
        if (status != null && !STATUSES.contains(status)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "must be one of " + String.join(", ", STATUSES));
        }
        String where = status == null ? "" : " WHERE fc.status = ?";
        Object[] filter = status == null ? new Object[0] : new Object[] {status};
        Long total = jdbc.queryForObject("SELECT count(*) FROM fraud_cases fc" + where, Long.class, filter);
        List<Object> args = new java.util.ArrayList<>(List.of(filter));
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<FraudCaseDto> content = jdbc.query(SELECT + where + " ORDER BY fc.created_at DESC, fc.id LIMIT ? OFFSET ?",
                (rs, i) -> row(rs).dto(), args.toArray());
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    @Transactional(readOnly = true)
    public FraudCaseDetailDto get(UUID caseId) {
        CaseRow row = find(caseId, false);
        FraudCaseDto c = row.dto();
        return new FraudCaseDetailDto(c.id(), c.status(), c.score(), c.transferId(), c.amount(),
                c.fromAccountNumberMasked(), c.toAccountNumberMasked(), c.toName(), c.customer(), c.topSignal(),
                c.createdAt(), c.decidedAt(), c.decisionReason(), row.memo() == null ? null : json.readTree(row.memo()),
                row.signals(), baseline(c.customer().userId(), c.transferId()));
    }

    @Transactional
    public IdempotencyService.Result<FraudCaseDto> release(UUID adminId, UUID key, String fingerprint, UUID caseId,
                                                          String reason) {
        return idempotency.execute(adminId, key, fingerprint, 200, FraudCaseDto.class, () -> {
            CaseRow row = openCase(caseId);
            transfers.completeReviewed(row.dto().transferId(), row.holdId());
            decide(adminId, caseId, "RELEASED", reason);
            notifications.notify(row.dto().customer().userId(), NotificationType.TRANSFER_UNDER_REVIEW,
                    "Transfer released", "Your transfer of " + Money.toJson(row.dto().amount()) + " INR to "
                            + row.dto().toName() + " was checked and sent.");
            audit.record(adminId, "FRAUD_RELEASED", "FRAUD_CASE", caseId.toString(), reason);
            return find(caseId, false).dto();
        });
    }

    @Transactional
    public IdempotencyService.Result<FraudCaseDto> reject(UUID adminId, UUID key, String fingerprint, UUID caseId,
                                                         String reason) {
        return idempotency.execute(adminId, key, fingerprint, 200, FraudCaseDto.class, () -> {
            CaseRow row = openCase(caseId);
            transfers.rejectReviewed(row.dto().transferId());
            ledger.releaseHold(row.holdId());
            decide(adminId, caseId, "REJECTED", reason);
            notifications.notify(row.dto().customer().userId(), NotificationType.TRANSFER_UNDER_REVIEW,
                    "Transfer stopped", "We stopped your transfer of " + Money.toJson(row.dto().amount()) + " INR to "
                            + row.dto().toName() + ". The money is available in your account again.");
            audit.record(adminId, "FRAUD_REJECTED", "FRAUD_CASE", caseId.toString(), reason);
            return find(caseId, false).dto();
        });
    }

    /** Locks the case row: two admins (or a double-click) can't decide it twice. */
    private CaseRow openCase(UUID caseId) {
        CaseRow row = find(caseId, true);
        if (!"OPEN".equals(row.dto().status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This case has already been decided");
        }
        return row;
    }

    private void decide(UUID adminId, UUID caseId, String status, String reason) {
        jdbc.update("UPDATE fraud_cases SET status = ?, decided_by = ?, decided_at = ?, decision_reason = ? WHERE id = ?",
                status, adminId, Timestamp.from(clock.instant()), reason, caseId);
    }

    /** The customer's normal pattern over the 90 days before the case (other transfers only). */
    private Baseline baseline(UUID userId, UUID transferId) {
        Instant now = clock.instant();
        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT COALESCE(avg(amount) FILTER (WHERE created_at >= ?), 0) AS avg,
                       count(*) FILTER (WHERE created_at >= ?) AS n30,
                       percentile_disc(0.1) WITHIN GROUP (ORDER BY extract(hour FROM created_at AT TIME ZONE 'Asia/Kolkata'))
                           FILTER (WHERE created_at >= ?) AS lo,
                       percentile_disc(0.9) WITHIN GROUP (ORDER BY extract(hour FROM created_at AT TIME ZONE 'Asia/Kolkata'))
                           FILTER (WHERE created_at >= ?) AS hi,
                       count(*) FILTER (WHERE created_at >= ?) AS n90
                  FROM transfers
                 WHERE user_id = ? AND internal = FALSE AND status = 'COMPLETED' AND id <> ?
                """, Timestamp.from(now.minus(Duration.ofDays(90))), Timestamp.from(now.minus(Duration.ofDays(30))),
                Timestamp.from(now.minus(Duration.ofDays(90))), Timestamp.from(now.minus(Duration.ofDays(90))),
                Timestamp.from(now.minus(Duration.ofDays(90))), userId, transferId);
        long n90 = ((Number) agg.get("n90")).longValue();
        String hours = n90 < 3 ? "Not enough history"
                : String.format("%02d:00–%02d:00", ((Number) agg.get("lo")).intValue(),
                Math.min(24, ((Number) agg.get("hi")).intValue() + 1));
        Timestamp joined = jdbc.queryForObject("SELECT created_at FROM users WHERE id = ?", Timestamp.class, userId);
        long ageDays = joined == null ? 0 : ChronoUnit.DAYS.between(joined.toInstant(), now);
        return new Baseline(Money.roundToPaise((BigDecimal) agg.get("avg")), ((Number) agg.get("n30")).longValue(),
                hours, ageDays);
    }

    private static final String SELECT = """
            SELECT fc.id, fc.status, fc.score, fc.transfer_id, fc.hold_id, fc.signals, fc.memo, fc.created_at,
                   fc.decided_at, fc.decision_reason, t.amount, t.from_account_number, t.to_account_number, t.to_name,
                   u.id AS user_id, u.full_name
              FROM fraud_cases fc
              JOIN transfers t ON t.id = fc.transfer_id
              JOIN users u ON u.id = fc.user_id
            """;

    private CaseRow find(UUID caseId, boolean lock) {
        return jdbc.query(SELECT + " WHERE fc.id = ?" + (lock ? " FOR UPDATE OF fc" : ""), (rs, i) -> row(rs), caseId)
                .stream().findFirst().orElseThrow(() -> ApiException.notFound("Case"));
    }

    private CaseRow row(ResultSet rs) throws SQLException {
        List<Signal> signals = json.readValue(rs.getString("signals"), SIGNALS);
        RiskService.Assessment a = new RiskService.Assessment(rs.getInt("score"), signals);
        Timestamp decided = rs.getTimestamp("decided_at");
        FraudCaseDto dto = new FraudCaseDto(rs.getObject("id", UUID.class), rs.getString("status"), rs.getInt("score"),
                rs.getObject("transfer_id", UUID.class), rs.getBigDecimal("amount"),
                AccountNumbers.mask(rs.getString("from_account_number")),
                AccountNumbers.mask(rs.getString("to_account_number")), rs.getString("to_name"),
                new Customer(rs.getObject("user_id", UUID.class), rs.getString("full_name")), a.topSignal(),
                rs.getTimestamp("created_at").toInstant(), decided == null ? null : decided.toInstant(),
                rs.getString("decision_reason"));
        return new CaseRow(dto, rs.getObject("hold_id", UUID.class), signals, rs.getString("memo"));
    }
}
