package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.payment.gateway.RazorpayApi;
import com.quantgen.payment.gateway.RazorpayProperties;
import com.quantgen.payment.gateway.RazorpaySignatures;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-PAY-10 / PRD 7.9 gateway top-ups in Razorpay test mode. Creating a top-up only creates a Razorpay order; money
 * arrives when either the client's {@code verify} (signature + Razorpay says captured, same amount) or Razorpay's
 * signed webhook confirms the payment. Both paths end in {@link #credit}, which locks the top-up row, so whichever
 * comes first credits and the other is a no-op. The account is never credited on the client's word alone.
 */
@Service
public class TopupService {

    private static final Logger log = LoggerFactory.getLogger(TopupService.class);

    public static final String FLAG = "razorpay";
    static final String PROVIDER = "RAZORPAY";

    public record TopupDto(UUID id, UUID accountId, BigDecimal amount, String currency, String status, String gateway,
                           String gatewayOrderId, String gatewayKeyId, UUID transactionId, String failureReason,
                           java.time.Instant createdAt, java.time.Instant updatedAt) {
    }

    private record Topup(UUID id, UUID userId, UUID accountId, BigDecimal amount, String status, String orderId) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final FeatureFlagService flags;
    private final RazorpayProperties props;
    private final RazorpayApi api;
    private final AuditService audit;
    private final NotificationService notifications;
    private final JsonMapper json;
    private final TransactionTemplate tx;

    public TopupService(JdbcTemplate jdbc, AccountService accounts, LedgerService ledger,
                        IdempotencyService idempotency, FeatureFlagService flags, RazorpayProperties props,
                        RazorpayApi api, AuditService audit, NotificationService notifications, JsonMapper json,
                        PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.flags = flags;
        this.props = props;
        this.api = api;
        this.audit = audit;
        this.notifications = notifications;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional
    public IdempotencyService.Result<TopupDto> create(UUID userId, UUID idempotencyKey, String fingerprint,
                                                      UUID accountId, BigDecimal rawAmount) {
        if (!props.apiConfigured()) {
            throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "Top-ups via the payment gateway are not set up");
        }
        if (!flags.isEnabled(FLAG)) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Top-ups via the payment gateway are switched off");
        }
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, TopupDto.class, () -> {
            AccountRef account = accounts.requireOwned(userId, accountId);
            if (account.status() == AccountStatus.FROZEN) {
                throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen");
            }
            if (account.status() != AccountStatus.ACTIVE || !account.isEveryday()) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This account cannot be topped up");
            }
            BigDecimal amount = Money.normalize(rawAmount);
            UUID id = UUID.randomUUID();
            String orderId;
            try {
                orderId = api.createOrder(amount.movePointRight(2).longValueExact(), id.toString());
            } catch (RazorpayApi.GatewayException e) {
                log.warn("Could not create a Razorpay order for top-up {}: {}", id, e.getMessage());
                throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "The payment gateway can't be reached; try again");
            }
            jdbc.update("INSERT INTO topups (id, user_id, account_id, amount, gateway_order_id) VALUES (?, ?, ?, ?, ?)",
                    id, userId, accountId, amount, orderId);
            return find(id).orElseThrow();
        });
    }

    /**
     * The client's confirmation after Checkout. Not one transaction on purpose: a failed verification is audited
     * (and must stay audited although the request fails), and the Razorpay lookup happens outside any row lock.
     */
    public TopupDto verify(UUID userId, UUID topupId, String paymentId, String signature) {
        Topup topup = load(topupId).filter(t -> t.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Top-up"));
        if ("CREDITED".equals(topup.status())) {
            return find(topupId).orElseThrow();
        }
        if (!props.apiConfigured()) {
            throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "Top-ups via the payment gateway are not set up");
        }
        if (!RazorpaySignatures.validPayment(topup.orderId(), paymentId, signature, props.keySecret())) {
            failedVerification(userId, topup, "bad signature");
        }
        RazorpayApi.Payment payment;
        try {
            payment = api.fetchPayment(paymentId);
        } catch (RazorpayApi.GatewayException e) {
            log.warn("Could not fetch Razorpay payment for top-up {}: {}", topupId, e.getMessage());
            throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "The payment gateway can't be reached; try again");
        }
        if (!matches(topup, payment)) {
            failedVerification(userId, topup, "payment " + payment.status() + " does not match the order");
        }
        return tx.execute(status -> credit(topupId, payment.id(), "verify"));
    }

    /**
     * Razorpay's webhook. A bad signature is 400; a repeated event id is acknowledged without effect; events about
     * unknown orders and unknown event types are acknowledged and ignored. The event id is recorded in the same
     * transaction as its effect, so a failure lets Razorpay's retry run it again.
     */
    public void webhook(byte[] rawBody, String signature, String eventIdHeader) {
        if (!props.webhookConfigured()) {
            throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "Razorpay webhooks are not set up");
        }
        if (!RazorpaySignatures.validWebhook(rawBody, signature, props.webhookSecret())) {
            log.warn("Rejected a Razorpay webhook with a bad signature");
            throw new ApiException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "Bad webhook signature");
        }
        JsonNode event = json.readTree(rawBody);
        String type = event.path("event").asString("");
        String eventId = eventIdHeader != null && !eventIdHeader.isBlank() ? eventIdHeader.trim()
                : "sha256:" + HexFormat.of().formatHex(sha256(rawBody)).substring(0, 48);
        tx.executeWithoutResult(status -> {
            int fresh = jdbc.update("INSERT INTO webhook_events (provider, event_id, event_type) VALUES (?, ?, ?) "
                    + "ON CONFLICT DO NOTHING", PROVIDER, eventId.length() > 64 ? eventId.substring(0, 64) : eventId,
                    type.length() > 40 ? type.substring(0, 40) : type);
            if (fresh == 0) {
                log.info("Ignoring repeated Razorpay event {}", eventId);
                return;
            }
            JsonNode p = event.path("payload").path("payment").path("entity");
            String orderId = p.path("order_id").asString(null);
            Optional<Topup> topup = orderId == null ? Optional.empty() : loadByOrder(orderId);
            if (topup.isEmpty()) {
                return;
            }
            switch (type) {
                case "payment.captured", "order.paid" -> {
                    RazorpayApi.Payment payment = new RazorpayApi.Payment(p.path("id").asString(), orderId,
                            p.path("amount").asLong(-1), p.path("currency").asString(""), p.path("status").asString(""));
                    if (matches(topup.get(), payment)) {
                        credit(topup.get().id(), payment.id(), "webhook");
                    } else {
                        log.warn("Razorpay event {} for top-up {} does not match the order; not credited", eventId,
                                topup.get().id());
                    }
                }
                case "payment.failed" -> {
                    String reason = p.path("error_description").asString("Payment failed");
                    jdbc.update("UPDATE topups SET status = 'FAILED', failure_reason = ?, updated_at = now() "
                            + "WHERE id = ? AND status = 'CREATED'", reason.length() > 200 ? reason.substring(0, 200)
                            : reason, topup.get().id());
                }
                default -> {
                    // Acknowledged and ignored.
                }
            }
        });
    }

    /**
     * Credits a captured payment exactly once: the row lock serializes verify and webhook, and a second caller sees
     * CREDITED and does nothing. A payment captured after the top-up was marked FAILED or EXPIRED is still credited,
     * because the customer's money did arrive. Must run in a transaction.
     */
    private TopupDto credit(UUID topupId, String paymentId, String via) {
        Topup topup = jdbc.query("SELECT * FROM topups WHERE id = ? FOR UPDATE", (rs, i) -> topup(rs), topupId)
                .stream().findFirst().orElseThrow();
        if (!"CREDITED".equals(topup.status())) {
            PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.TOPUP, "Added via Razorpay",
                            topup.userId())
                    .debit(SystemAccount.GATEWAY_CLEARING.id(), topup.amount(), null)
                    .credit(topup.accountId(), topup.amount(), "Razorpay")
                    .build());
            jdbc.update("""
                    UPDATE topups SET status = 'CREDITED', gateway_payment_id = ?, journal_entry_id = ?,
                           transaction_id = ?, failure_reason = NULL, updated_at = now()
                     WHERE id = ?
                    """, paymentId, entry.journalEntryId(), entry.lineFor(topup.accountId()).lineId(), topupId);
            AccountRef account = accounts.findById(topup.accountId()).orElseThrow();
            audit.record(topup.userId(), "TOPUP_CREDITED", "TOPUP", topupId.toString(),
                    "amount=" + Money.toJson(topup.amount()) + " account=" + AccountNumbers.mask(account.accountNumber())
                            + " via=" + via);
            notifications.notify(topup.userId(), NotificationType.MONEY_RECEIVED, "Money added",
                    Money.toJson(topup.amount()) + " INR was added to account "
                            + AccountNumbers.mask(account.accountNumber()) + " via Razorpay.");
        }
        return find(topupId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public TopupDto get(UUID userId, UUID topupId) {
        return load(topupId).filter(t -> t.userId().equals(userId)).flatMap(t -> find(topupId))
                .orElseThrow(() -> ApiException.notFound("Top-up"));
    }

    @Transactional(readOnly = true)
    public PageDto<TopupDto> list(UUID userId, UUID accountId, Pageable pageable) {
        accounts.requireOwned(userId, accountId);
        Long total = jdbc.queryForObject("SELECT count(*) FROM topups WHERE account_id = ?", Long.class, accountId);
        List<TopupDto> content = jdbc.query("SELECT * FROM topups WHERE account_id = ? "
                + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?", (rs, i) -> dto(rs), accountId,
                pageable.getPageSize(), pageable.getOffset());
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    /** An unpaid top-up expires after 30 minutes (a late capture is still credited). */
    @Scheduled(fixedDelayString = "${qg.payments.topup-expiry-sweep-millis:60000}")
    @Transactional
    public int expire() {
        return jdbc.update("UPDATE topups SET status = 'EXPIRED', failure_reason = 'Not paid within 30 minutes', "
                + "updated_at = now() WHERE status = 'CREATED' AND created_at < now() - interval '30 minutes'");
    }

    private static boolean matches(Topup topup, RazorpayApi.Payment payment) {
        return "captured".equals(payment.status()) && topup.orderId().equals(payment.orderId())
                && "INR".equals(payment.currency())
                && payment.amountPaise() == topup.amount().movePointRight(2).longValueExact();
    }

    private void failedVerification(UUID userId, Topup topup, String why) {
        audit.record(userId, "TOPUP_VERIFICATION_FAILED", "TOPUP", topup.id().toString(), why);
        throw new ApiException(ErrorCode.PAYMENT_VERIFICATION_FAILED, "The payment could not be verified");
    }

    private Optional<Topup> load(UUID id) {
        return jdbc.query("SELECT * FROM topups WHERE id = ?", (rs, i) -> topup(rs), id).stream().findFirst();
    }

    private Optional<Topup> loadByOrder(String orderId) {
        return jdbc.query("SELECT * FROM topups WHERE gateway_order_id = ?", (rs, i) -> topup(rs), orderId).stream()
                .findFirst();
    }

    private Optional<TopupDto> find(UUID id) {
        return jdbc.query("SELECT * FROM topups WHERE id = ?", (rs, i) -> dto(rs), id).stream().findFirst();
    }

    private static Topup topup(ResultSet rs) throws SQLException {
        return new Topup(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("status"),
                rs.getString("gateway_order_id"));
    }

    private TopupDto dto(ResultSet rs) throws SQLException {
        return new TopupDto(rs.getObject("id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getBigDecimal("amount"), "INR", rs.getString("status"), PROVIDER, rs.getString("gateway_order_id"),
                props.keyId(), rs.getObject("transaction_id", UUID.class), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static byte[] sha256(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(data);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
