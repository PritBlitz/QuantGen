package com.quantgen.card;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.security.UserRateLimiter;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;

/**
 * F-CARD-4 disputes (PRD 12.11). The cardholder disputes (part of) a captured payment; an admin refunds or rejects
 * it. Lock order: opening locks the authorization; resolving locks the dispute, then the authorization, then the
 * ledger accounts; a sandbox refund locks the authorization and only reads open disputes. A refund is a REFUND entry
 * CARD_CLEARING → customer that also raises the authorization's refunded amount, so it can't be refunded twice.
 */
@Service
public class DisputeService {

    static final UserRateLimiter.Limit OPEN_LIMIT = new UserRateLimiter.Limit("card-dispute", 10, Duration.ofMinutes(1));
    static final Duration WINDOW = Duration.ofDays(60);
    static final int MAX_OPEN = 20;

    public enum Reason { NOT_RECEIVED, NOT_AS_DESCRIBED, DUPLICATE, NOT_RECOGNISED, OTHER }

    public enum Outcome { REFUNDED, REJECTED }

    public record CardPaymentDto(UUID authId, String merchant, String category, BigDecimal amount,
                                 BigDecimal capturedAmount, BigDecimal refundedAmount, BigDecimal disputableAmount,
                                 String status, Instant capturedAt, UUID openDisputeId) {
    }

    public record CardDisputeDto(UUID id, UUID cardId, UUID authId, String last4, String merchant, BigDecimal amount,
                                 String reason, String note, String status, String resolutionReason, Instant createdAt,
                                 Instant resolvedAt) {
    }

    public record AdminCardDisputeDto(UUID id, UUID cardId, UUID authId, String last4, String merchant,
                                      BigDecimal amount, String reason, String note, String status,
                                      String resolutionReason, Instant createdAt, Instant resolvedAt, UUID customerId) {
    }

    private record Auth(UUID id, UUID accountId, String last4, String merchant, BigDecimal captured, BigDecimal refunded,
                        Instant capturedAt) {
    }

    private final JdbcTemplate jdbc;
    private final CardService cards;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final AuditService audit;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionTemplate tx;

    DisputeService(JdbcTemplate jdbc, CardService cards, AccountService accounts, LedgerService ledger,
                   IdempotencyService idempotency, NotificationService notifications, AuditService audit,
                   UserRateLimiter rateLimiter, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ cardholder

    /** Captured (or refunded) payments on your own card, newest first; someone else's card is 404. */
    public PageDto<CardPaymentDto> payments(UUID userId, UUID cardId, Pageable pageable) {
        requireOwnCard(userId, cardId);
        Long total = jdbc.queryForObject("SELECT count(*) FROM card_authorizations WHERE card_id = ? "
                + "AND status IN ('CAPTURED', 'REFUNDED')", Long.class, cardId);
        List<CardPaymentDto> content = jdbc.query("""
                SELECT a.id, a.merchant, a.category, a.amount, a.captured_amount, a.refunded_amount, a.status,
                       a.captured_at, d.id AS dispute_id, COALESCE(d.amount, 0) AS disputed
                  FROM card_authorizations a
                  LEFT JOIN card_disputes d ON d.authorization_id = a.id AND d.status = 'OPEN'
                 WHERE a.card_id = ? AND a.status IN ('CAPTURED', 'REFUNDED')
                 ORDER BY a.captured_at DESC, a.id DESC LIMIT ? OFFSET ?
                """, (rs, i) -> new CardPaymentDto(rs.getObject("id", UUID.class), rs.getString("merchant"),
                rs.getString("category"), rs.getBigDecimal("amount"), rs.getBigDecimal("captured_amount"),
                rs.getBigDecimal("refunded_amount"), rs.getBigDecimal("captured_amount")
                .subtract(rs.getBigDecimal("refunded_amount")).subtract(rs.getBigDecimal("disputed")),
                rs.getString("status"), rs.getTimestamp("captured_at").toInstant(),
                rs.getObject("dispute_id", UUID.class)), cardId, pageable.getPageSize(), pageable.getOffset());
        return page(content, total, pageable);
    }

    public CardDisputeDto open(UUID userId, UUID cardId, UUID authId, Reason reason, BigDecimal rawAmount, String rawNote) {
        cards.requireEnabled();
        rateLimiter.acquire(OPEN_LIMIT, userId);
        return tx.execute(st -> {
            requireOwnCard(userId, cardId);
            // Locked, so the disputable amount can't change under us (a refund or another dispute waits).
            Auth auth = jdbc.query("""
                    SELECT a.id, c.account_id, c.last4, a.merchant, a.captured_amount, a.refunded_amount, a.captured_at
                      FROM card_authorizations a JOIN cards c ON c.id = a.card_id
                     WHERE a.id = ? AND a.card_id = ? AND a.status IN ('CAPTURED', 'REFUNDED')
                       FOR UPDATE OF a
                    """, (rs, i) -> auth(rs), authId, cardId).stream().findFirst()
                    .orElseThrow(() -> ApiException.notFound("Payment"));
            if (auth.capturedAt().plus(WINDOW).isBefore(clock.instant())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Payments can be disputed within 60 days");
            }
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM card_disputes WHERE "
                    + "authorization_id = ? AND status = 'OPEN')", Boolean.class, authId))) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This payment already has an open dispute");
            }
            jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
            Integer open = jdbc.queryForObject("SELECT count(*) FROM card_disputes WHERE user_id = ? AND status = 'OPEN'",
                    Integer.class, userId);
            if (open != null && open >= MAX_OPEN) {
                throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can have at most " + MAX_OPEN + " open disputes");
            }
            BigDecimal disputable = auth.captured().subtract(auth.refunded()).setScale(2, RoundingMode.HALF_EVEN);
            BigDecimal amount = rawAmount == null ? disputable : rawAmount;
            if (amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(disputable) > 0) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "amount",
                        "must be more than 0 and at most " + Money.toJson(disputable));
            }
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            String note = rawNote == null || rawNote.isBlank() ? null : rawNote.strip();
            UUID id = jdbc.queryForObject("""
                    INSERT INTO card_disputes (card_id, authorization_id, user_id, amount, reason, note)
                    VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, cardId, authId, userId, amount, reason.name(), note);
            // Never the note: it's the customer's free text.
            audit.record(userId, "CARD_DISPUTE_OPENED", "CARD_DISPUTE", id.toString(),
                    "•••• " + auth.last4() + " " + Money.toJson(amount) + " " + reason.name());
            return dispute(id, userId);
        });
    }

    public List<CardDisputeDto> list(UUID userId) {
        return jdbc.query(SELECT + " WHERE d.user_id = ? ORDER BY d.created_at DESC, d.id DESC",
                (rs, i) -> dto(rs), userId);
    }

    // ------------------------------------------------------------------ admin

    public PageDto<AdminCardDisputeDto> adminList(String status, Pageable pageable) {
        if (status != null && !List.of("OPEN", "REFUNDED", "REJECTED").contains(status)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "must be OPEN, REFUNDED or REJECTED");
        }
        String where = status == null ? "" : " WHERE d.status = ?";
        List<Object> args = new ArrayList<>();
        if (status != null) {
            args.add(status);
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM card_disputes d" + where, Long.class, args.toArray());
        args.add(pageable.getPageSize());
        args.add(pageable.getOffset());
        List<AdminCardDisputeDto> content = jdbc.query(SELECT + where + " ORDER BY d.created_at, d.id LIMIT ? OFFSET ?",
                (rs, i) -> {
                    CardDisputeDto d = dto(rs);
                    return new AdminCardDisputeDto(d.id(), d.cardId(), d.authId(), d.last4(), d.merchant(), d.amount(),
                            d.reason(), d.note(), d.status(), d.resolutionReason(), d.createdAt(), d.resolvedAt(),
                            rs.getObject("user_id", UUID.class));
                }, args.toArray());
        return page(content, total, pageable);
    }

    public IdempotencyService.Result<CardDisputeDto> resolve(UUID adminId, UUID key, String fingerprint, UUID disputeId,
                                                            Outcome outcome, String rawReason) {
        String reason = rawReason.strip();
        return tx.execute(st -> idempotency.execute(adminId, key, fingerprint, 200, CardDisputeDto.class, () -> {
            List<UUID[]> locked = jdbc.query("SELECT authorization_id, user_id FROM card_disputes WHERE id = ? "
                    + "AND status = 'OPEN' FOR UPDATE", (rs, i) -> new UUID[] {rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class)}, disputeId);
            if (locked.isEmpty()) {
                if (jdbc.queryForList("SELECT id FROM card_disputes WHERE id = ?", UUID.class, disputeId).isEmpty()) {
                    throw ApiException.notFound("Dispute");
                }
                throw new ApiException(ErrorCode.INVALID_STATE, "This dispute has already been resolved");
            }
            UUID authId = locked.getFirst()[0];
            UUID customerId = locked.getFirst()[1];
            Auth auth = jdbc.query("""
                    SELECT a.id, c.account_id, c.last4, a.merchant, a.captured_amount, a.refunded_amount, a.captured_at
                      FROM card_authorizations a JOIN cards c ON c.id = a.card_id WHERE a.id = ? FOR UPDATE OF a
                    """, (rs, i) -> auth(rs), authId).getFirst();
            BigDecimal amount = jdbc.queryForObject("SELECT amount FROM card_disputes WHERE id = ?", BigDecimal.class,
                    disputeId);
            UUID entryId = null;
            if (outcome == Outcome.REFUNDED) {
                AccountRef account = accounts.findById(auth.accountId()).orElseThrow();
                if (account.status() == AccountStatus.CLOSED) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "The account is closed; reject the dispute instead");
                }
                BigDecimal remaining = auth.captured().subtract(auth.refunded());
                if (amount.compareTo(remaining) > 0) {
                    throw new ApiException(ErrorCode.INVALID_STATE, "Only " + Money.toJson(remaining)
                            + " of this payment is left to refund");
                }
                PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.REFUND,
                                "Dispute refund · " + auth.merchant(), adminId)
                        .debit(SystemAccount.CARD_CLEARING.id(), amount, null)
                        .credit(auth.accountId(), amount, auth.merchant())
                        .build());
                entryId = entry.journalEntryId();
                jdbc.update("INSERT INTO card_refunds (authorization_id, amount, journal_entry_id) VALUES (?, ?, ?)",
                        authId, amount, entryId);
                BigDecimal refunded = auth.refunded().add(amount);
                jdbc.update("UPDATE card_authorizations SET refunded_amount = ?, status = ? WHERE id = ?", refunded,
                        refunded.compareTo(auth.captured()) == 0 ? "REFUNDED" : "CAPTURED", authId);
            }
            jdbc.update("""
                    UPDATE card_disputes SET status = ?, resolution_reason = ?, resolved_by = ?, refund_entry_id = ?,
                           resolved_at = ? WHERE id = ?
                    """, outcome.name(), reason, adminId, entryId, Timestamp.from(clock.instant()), disputeId);
            audit.record(adminId, "CARD_DISPUTE_RESOLVED", "CARD_DISPUTE", disputeId.toString(),
                    "•••• " + auth.last4() + " " + Money.toJson(amount) + " " + outcome.name() + ": " + reason);
            notifications.notify(customerId, NotificationType.CARD_DISPUTE_RESOLVED,
                    outcome == Outcome.REFUNDED ? "Dispute refunded" : "Dispute rejected",
                    outcome == Outcome.REFUNDED
                            ? "₹" + Money.toJson(amount) + " from " + auth.merchant() + " is back on card •••• "
                            + auth.last4() + "."
                            : "Your dispute of ₹" + Money.toJson(amount) + " at " + auth.merchant()
                            + " was rejected: " + reason);
            return dispute(disputeId, customerId);
        }));
    }

    // ------------------------------------------------------------------ for the sandbox

    /** What open disputes hold back on this authorization; a sandbox refund can't return it too. */
    BigDecimal openDisputed(UUID authId) {
        BigDecimal sum = jdbc.queryForObject("SELECT COALESCE(sum(amount), 0) FROM card_disputes "
                + "WHERE authorization_id = ? AND status = 'OPEN'", BigDecimal.class, authId);
        return sum == null ? BigDecimal.ZERO : sum;
    }

    // ------------------------------------------------------------------ rows

    private static final String SELECT = """
            SELECT d.id, d.card_id, d.authorization_id, d.user_id, c.last4, a.merchant, d.amount, d.reason, d.note,
                   d.status, d.resolution_reason, d.created_at, d.resolved_at
              FROM card_disputes d JOIN cards c ON c.id = d.card_id
              JOIN card_authorizations a ON a.id = d.authorization_id
            """;

    private void requireOwnCard(UUID userId, UUID cardId) {
        if (jdbc.queryForList("SELECT id FROM cards WHERE id = ? AND user_id = ?", UUID.class, cardId, userId).isEmpty()) {
            throw ApiException.notFound("Card");
        }
    }

    private CardDisputeDto dispute(UUID id, UUID userId) {
        return jdbc.query(SELECT + " WHERE d.id = ? AND d.user_id = ?", (rs, i) -> dto(rs), id, userId).getFirst();
    }

    private static CardDisputeDto dto(ResultSet rs) throws SQLException {
        Timestamp resolved = rs.getTimestamp("resolved_at");
        return new CardDisputeDto(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getObject("authorization_id", UUID.class), rs.getString("last4"), rs.getString("merchant"),
                Money.roundToPaise(rs.getBigDecimal("amount")), rs.getString("reason"), rs.getString("note"),
                rs.getString("status"), rs.getString("resolution_reason"), rs.getTimestamp("created_at").toInstant(),
                resolved == null ? null : resolved.toInstant());
    }

    private static Auth auth(ResultSet rs) throws SQLException {
        Timestamp captured = rs.getTimestamp("captured_at");
        return new Auth(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class), rs.getString("last4"),
                rs.getString("merchant"), rs.getBigDecimal("captured_amount"), rs.getBigDecimal("refunded_amount"),
                captured == null ? Instant.EPOCH : captured.toInstant());
    }

    private static <T> PageDto<T> page(List<T> content, Long total, Pageable pageable) {
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }
}
