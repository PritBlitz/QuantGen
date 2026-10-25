package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.web.PageDto;
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.domain.TransferSource;

/**
 * F-PAY-8 payment requests: a user with a handle asks another handle for an amount. Nothing moves until the payer
 * pays, which is an ordinary transfer (limits, cooling and step-up all apply) linked to the request. Requests
 * expire after 7 days; a requester has at most 20 open at a time; the payer can decline and block the requester.
 */
@Service
public class PaymentRequestService {

    public static final Duration TTL = Duration.ofDays(7);
    public static final int MAX_OPEN_OUTGOING = 20;

    public enum Direction { INCOMING, OUTGOING }

    public record PaymentRequestDto(UUID id, String fromHandle, String toHandle, BigDecimal amount, String note,
                                    String status, UUID transferId, Instant expiresAt, Instant createdAt) {
    }

    private record Row(PaymentRequestDto dto, UUID requesterId, UUID payerId) {
    }

    /** Status with lazy expiry, so a request past its time reads EXPIRED before the sweep has run. */
    private static final String COLUMNS = """
            id, requester_user_id, payer_user_id, from_handle, to_handle, amount, note, transfer_id, expires_at,
            created_at, CASE WHEN status = 'OPEN' AND expires_at <= now() THEN 'EXPIRED' ELSE status END AS status
            """;

    private final JdbcTemplate jdbc;
    private final HandleService handles;
    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final UserService users;
    private final Clock clock;

    public PaymentRequestService(JdbcTemplate jdbc, HandleService handles, TransferService transfers,
                                 IdempotencyService idempotency, NotificationService notifications, UserService users,
                                 Clock clock) {
        this.jdbc = jdbc;
        this.handles = handles;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public PaymentRequestDto create(UUID userId, String toHandle, BigDecimal rawAmount, String rawNote) {
        return createInternal(userId, toHandle, rawAmount, rawNote, null);
    }

    /**
     * Same as {@link #create} inside the caller's transaction. {@code splitGroupId} links a split-group settle-up
     * request to its group, so paying it settles the debt there.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PaymentRequestDto createInternal(UUID userId, String toHandle, BigDecimal rawAmount, String rawNote,
                                            UUID splitGroupId) {
        String fromHandle = handles.handleOf(userId);
        if (fromHandle == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Set your handle before requesting money");
        }
        UUID payerId = handles.userFor(toHandle).orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST,
                "toHandle", "no account with this handle"));
        if (payerId.equals(userId)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "toHandle", "you can't request money from yourself");
        }
        Boolean blocked = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM blocked_requesters WHERE user_id = ? AND blocked_user_id = ?)",
                Boolean.class, payerId, userId);
        if (Boolean.TRUE.equals(blocked)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "toHandle", "can't send a request to this handle");
        }
        // Serialize this user's request creation so concurrent calls can't pass the open-request cap together.
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        Integer open = jdbc.queryForObject("SELECT count(*) FROM payment_requests WHERE requester_user_id = ? "
                + "AND status = 'OPEN' AND expires_at > now()", Integer.class, userId);
        if (open != null && open >= MAX_OPEN_OUTGOING) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "You can have at most " + MAX_OPEN_OUTGOING + " open requests; cancel one first");
        }
        BigDecimal amount = Money.normalize(rawAmount);
        String note = rawNote == null || rawNote.isBlank() ? null : rawNote.trim();
        Instant now = clock.instant();
        UUID id = jdbc.queryForObject("""
                INSERT INTO payment_requests (requester_user_id, payer_user_id, from_handle, to_handle, amount, note,
                                              expires_at, created_at, split_group_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, userId, payerId, fromHandle, handles.handleOf(payerId), amount, note,
                Timestamp.from(now.plus(TTL)), Timestamp.from(now), splitGroupId);
        String requesterName = users.getUser(userId).fullName();
        notifications.notify(payerId, NotificationType.PAYMENT_REQUEST, "Payment request",
                requesterName + " (" + fromHandle + ") asked you for " + Money.toJson(amount) + " INR"
                        + (note != null ? ": " + note : "") + ".");
        return find(id, userId).orElseThrow().dto();
    }

    @Transactional(readOnly = true)
    public PageDto<PaymentRequestDto> list(UUID userId, Direction direction, String status, Pageable pageable) {
        String who = direction == Direction.OUTGOING ? "requester_user_id" : "payer_user_id";
        List<Object> args = new ArrayList<>(List.of(userId));
        String filter = "";
        if (status != null) {
            filter = " AND status = ?";
            args.add(status);
        }
        String base = "FROM (SELECT " + COLUMNS + " FROM payment_requests WHERE " + who + " = ?) r WHERE TRUE" + filter;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<PaymentRequestDto> content = jdbc.query("SELECT * " + base + " ORDER BY created_at DESC, id DESC "
                + "LIMIT ? OFFSET ?", (rs, i) -> map(rs).dto(), pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    /** Pays an incoming request from one of the payer's accounts: a normal transfer with source REQUEST. */
    @Transactional
    public IdempotencyService.Result<TransferDto> pay(UUID userId, UUID requestId, UUID idempotencyKey,
                                                      String fingerprint, UUID fromAccountId, String stepUpToken) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, TransferDto.class, () -> {
            Row request = lock(requestId).filter(r -> r.payerId().equals(userId))
                    .orElseThrow(() -> ApiException.notFound("Payment request"));
            requireOpen(request);
            AccountRef to = handles.accountOf(request.requesterId())
                    .filter(a -> a.status() != AccountStatus.CLOSED)
                    .orElseThrow(() -> new ApiException(ErrorCode.INVALID_STATE,
                            "The requester can't receive payments right now"));
            PaymentRequestDto dto = request.dto();
            String note = dto.note() != null ? dto.note() : "Payment request from " + dto.fromHandle();
            TransferDto transfer = transfers.execute(userId, fromAccountId, new TransferService.Target(to, "id"),
                    dto.amount(), note, TransferSource.REQUEST, stepUpToken, false);
            jdbc.update("UPDATE payment_requests SET status = 'PAID', transfer_id = ?, resolved_at = ? WHERE id = ?",
                    transfer.id(), Timestamp.from(clock.instant()), requestId);
            return transfer;
        });
    }

    /** The payer declines (optionally blocking the requester), or the requester cancels. */
    @Transactional
    public PaymentRequestDto decline(UUID userId, UUID requestId, boolean block) {
        Row request = lock(requestId)
                .filter(r -> r.payerId().equals(userId) || r.requesterId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Payment request"));
        requireOpen(request);
        Timestamp now = Timestamp.from(clock.instant());
        if (request.payerId().equals(userId)) {
            jdbc.update("UPDATE payment_requests SET status = 'DECLINED', resolved_at = ? WHERE id = ?", now, requestId);
            if (block) {
                jdbc.update("INSERT INTO blocked_requesters (user_id, blocked_user_id) VALUES (?, ?) "
                        + "ON CONFLICT DO NOTHING", userId, request.requesterId());
                // A block also clears the requester's other open requests to this user.
                jdbc.update("UPDATE payment_requests SET status = 'DECLINED', resolved_at = ? WHERE status = 'OPEN' "
                        + "AND requester_user_id = ? AND payer_user_id = ?", now, request.requesterId(), userId);
            }
        } else {
            jdbc.update("UPDATE payment_requests SET status = 'CANCELLED', resolved_at = ? WHERE id = ?", now, requestId);
        }
        return find(requestId, userId).orElseThrow().dto();
    }

    /** Someone you blocked from sending you requests: their current handle and masked name. */
    public record BlockedDto(String handle, String displayName, Instant blockedAt) {
    }

    @Transactional(readOnly = true)
    public List<BlockedDto> blocked(UUID userId) {
        return jdbc.query("""
                SELECT b.blocked_user_id, b.created_at, h.handle, u.full_name FROM blocked_requesters b
                  JOIN users u ON u.id = b.blocked_user_id LEFT JOIN handles h ON h.user_id = b.blocked_user_id
                 WHERE b.user_id = ? ORDER BY b.created_at DESC
                """, (rs, i) -> new BlockedDto(rs.getString("handle"), HandleService.maskName(rs.getString("full_name")),
                rs.getTimestamp("created_at").toInstant()), userId);
    }

    /** Lets a blocked requester ask you for money again. Not blocked (or no such handle) → 404. */
    @Transactional
    public void unblock(UUID userId, String handle) {
        UUID other = handles.userFor(handle).orElseThrow(() -> ApiException.notFound("Blocked requester"));
        if (jdbc.update("DELETE FROM blocked_requesters WHERE user_id = ? AND blocked_user_id = ?", userId, other) == 0) {
            throw ApiException.notFound("Blocked requester");
        }
    }

    /** Marks requests past their expiry as EXPIRED (reads already show them as expired). */
    @Scheduled(fixedDelayString = "${qg.payments.request-expiry-sweep-millis:600000}")
    @Transactional
    public int expire() {
        return jdbc.update("UPDATE payment_requests SET status = 'EXPIRED', resolved_at = now() "
                + "WHERE status = 'OPEN' AND expires_at <= now()");
    }

    private static void requireOpen(Row request) {
        if (!"OPEN".equals(request.dto().status())) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This request is no longer open");
        }
    }

    private Optional<Row> lock(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payment_requests WHERE id = ? FOR UPDATE",
                (rs, i) -> map(rs), id).stream().findFirst();
    }

    private Optional<Row> find(UUID id, UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM payment_requests WHERE id = ? "
                + "AND (requester_user_id = ? OR payer_user_id = ?)", (rs, i) -> map(rs), id, userId, userId)
                .stream().findFirst();
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(new PaymentRequestDto(rs.getObject("id", UUID.class), rs.getString("from_handle"),
                rs.getString("to_handle"), rs.getBigDecimal("amount"), rs.getString("note"), rs.getString("status"),
                rs.getObject("transfer_id", UUID.class), rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant()),
                rs.getObject("requester_user_id", UUID.class), rs.getObject("payer_user_id", UUID.class));
    }
}
