package com.quantgen.card;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.card.CardService.CardRow;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.security.UserRateLimiter;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.risk.RiskService;

/**
 * F-CARD-3 merchant sandbox ("QuantGen Store"). Authorize = card checks + controls + a hold on the available
 * balance; capture = a CARD entry customer → CARD_CLEARING that settles the hold; refund = a REFUND entry back.
 * Every attempt is recorded, declines included, and a decline on a real card notifies its owner.
 */
@Service
public class SandboxService {

    static final UserRateLimiter.Limit AUTHORIZE_LIMIT = new UserRateLimiter.Limit("card-authorize", 20, Duration.ofMinutes(1));
    static final UserRateLimiter.Limit SETTLE_LIMIT = new UserRateLimiter.Limit("card-settle", 60, Duration.ofMinutes(1));
    /** Card authorizations hold money for at most 7 days (ARCHITECTURE 12.3). */
    static final Duration HOLD_TTL = Duration.ofDays(7);
    static final String HOLD_REASON = "CARD_AUTH";

    static final String DETAILS_MISMATCH = "Card details don't match";

    public record AuthorizeRequest(String cardNumber, String expiry, String cvv, BigDecimal amount, String merchant,
                                   MerchantCategory category) {
    }

    public record AuthorizationDto(UUID authId, String status, String reason) {
    }

    public record SettlementDto(UUID authId, String status, BigDecimal amount, BigDecimal capturedAmount,
                                BigDecimal refundedAmount) {
    }

    private record Auth(UUID id, UUID cardId, UUID accountId, UUID holdId, String merchant, BigDecimal amount,
                        BigDecimal captured, BigDecimal refunded, String status) {
    }

    private final JdbcTemplate jdbc;
    private final CardService cards;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final NotificationService notifications;
    private final UserRateLimiter rateLimiter;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final RiskService risk;
    private final DisputeService disputes;

    SandboxService(JdbcTemplate jdbc, CardService cards, AccountService accounts, LedgerService ledger,
                   IdempotencyService idempotency, NotificationService notifications, UserRateLimiter rateLimiter,
                   PlatformTransactionManager txManager, Clock clock, RiskService risk, DisputeService disputes) {
        this.jdbc = jdbc;
        this.cards = cards;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.notifications = notifications;
        this.rateLimiter = rateLimiter;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.risk = risk;
        this.disputes = disputes;
    }

    // ------------------------------------------------------------------ authorize

    public IdempotencyService.Result<AuthorizationDto> authorize(UUID merchantUserId, UUID key, String fingerprint,
                                                                AuthorizeRequest req) {
        cards.requireEnabled();
        rateLimiter.acquire(AUTHORIZE_LIMIT, merchantUserId);
        try {
            return tx.execute(st -> idempotency.execute(merchantUserId, key, fingerprint, 200, AuthorizationDto.class,
                    () -> decide(merchantUserId, req, null)));
        } catch (ApiException e) {
            // The hold lost a race (money spent or account frozen since the checks): that transaction rolled back,
            // so record the decline in a fresh one under the same idempotency key.
            String reason = switch (e.code()) {
                case INSUFFICIENT_FUNDS -> "Insufficient funds";
                case ACCOUNT_FROZEN -> "Account is frozen";
                default -> null;
            };
            if (reason == null) {
                throw e;
            }
            return tx.execute(st -> idempotency.execute(merchantUserId, key, fingerprint, 200, AuthorizationDto.class,
                    () -> decide(merchantUserId, req, reason)));
        }
    }

    private AuthorizationDto decide(UUID merchantUserId, AuthorizeRequest req, String forcedDecline) {
        String pan = req.cardNumber().replace(" ", "");
        Optional<CardRow> found = CardService.luhnValid(pan) ? cards.findByPan(pan) : Optional.empty();
        if (found.isEmpty()) {
            return decline(merchantUserId, null, req, DETAILS_MISMATCH);
        }
        CardRow card = found.get();
        long window = cards.currentWindow();
        boolean cvvOk = req.cvv().equals(cards.cvv(card.pan(), window)) || req.cvv().equals(cards.cvv(card.pan(), window - 1));
        if (!card.expiry().equals(req.expiry()) || !cvvOk) {
            return decline(merchantUserId, card, req, DETAILS_MISMATCH);
        }
        if (forcedDecline != null) {
            return decline(merchantUserId, card, req, forcedDecline);
        }
        BigDecimal amount = Money.normalize(req.amount());
        AccountRef account = accounts.findById(card.accountId()).orElseThrow();
        String reason = cards.isExpired(card.expiry()) ? "Card has expired"
                : card.controls().frozen() ? "Card is frozen"
                : !card.controls().online() ? "Online payments are off"
                : card.controls().blockedCategories().contains(req.category()) ? "Merchant category is blocked"
                : amount.compareTo(card.controls().perTxnLimit()) > 0 ? "Over the per-transaction limit"
                : account.status() == AccountStatus.FROZEN ? "Account is frozen"
                : account.status() == AccountStatus.CLOSED ? "Account is closed"
                : available(account.id()).compareTo(amount) < 0 ? "Insufficient funds"
                : null;
        if (reason == null && risk.needsReview(risk.scoreCard(card.id(), amount))) {
            reason = "Suspected fraud"; // F-RISK-1: rules only (D-030)
        }
        if (reason != null) {
            return decline(merchantUserId, card, req, reason);
        }
        UUID authId = UUID.randomUUID();
        // placeHold re-checks the available balance under the account's row lock.
        UUID holdId = ledger.placeHold(account.id(), amount, HOLD_REASON, "card-auth:" + authId,
                clock.instant().plus(HOLD_TTL));
        jdbc.update("""
                INSERT INTO card_authorizations (id, card_id, merchant_user_id, merchant, category, amount, status, hold_id)
                VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZED', ?)
                """, authId, card.id(), merchantUserId, req.merchant(), req.category().name(), amount, holdId);
        return new AuthorizationDto(authId, "APPROVED", null);
    }

    private AuthorizationDto decline(UUID merchantUserId, CardRow card, AuthorizeRequest req, String reason) {
        UUID authId = jdbc.queryForObject("""
                INSERT INTO card_authorizations (card_id, merchant_user_id, merchant, category, amount, status, decline_reason)
                VALUES (?, ?, ?, ?, ?, 'DECLINED', ?) RETURNING id
                """, UUID.class, card == null ? null : card.id(), merchantUserId, req.merchant(), req.category().name(),
                Money.normalize(req.amount()), reason);
        if (card != null) {
            notifications.notify(card.userId(), NotificationType.CARD_DECLINED, "Card payment declined",
                    "₹" + Money.toJson(req.amount()) + " at " + req.merchant() + " on card •••• " + card.last4()
                            + ": " + reason + ".");
        }
        return new AuthorizationDto(authId, "DECLINED", reason);
    }

    private BigDecimal available(UUID accountId) {
        BigDecimal balance = ledger.balancesOf(List.of(accountId)).get(accountId);
        return balance.subtract(ledger.holdsTotals(List.of(accountId)).get(accountId));
    }

    // ------------------------------------------------------------------ capture and refund

    public IdempotencyService.Result<SettlementDto> capture(UUID merchantUserId, UUID key, String fingerprint,
                                                           UUID authId, BigDecimal amount) {
        cards.requireEnabled();
        rateLimiter.acquire(SETTLE_LIMIT, merchantUserId);
        return tx.execute(st -> idempotency.execute(merchantUserId, key, fingerprint, 200, SettlementDto.class, () -> {
            Auth auth = lock(merchantUserId, authId);
            if (!"AUTHORIZED".equals(auth.status())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Only an approved, uncaptured payment can be captured");
            }
            BigDecimal capture = amount == null ? auth.amount() : Money.normalize(amount);
            if (capture.compareTo(auth.amount()) > 0) {
                throw ApiException.fieldError(org.springframework.http.HttpStatus.BAD_REQUEST, "amount",
                        "can't be more than the authorized amount");
            }
            String holdStatus = jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, auth.holdId());
            if (!"ACTIVE".equals(holdStatus)) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This authorization has expired");
            }
            // Settles the whole hold: a partial capture frees the rest of the authorized amount.
            PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.CARD, "Card payment · " + auth.merchant(), null)
                    .debit(auth.accountId(), capture, auth.merchant())
                    .credit(SystemAccount.CARD_CLEARING.id(), capture, null)
                    .captureHold(auth.holdId())
                    .build());
            jdbc.update("""
                    UPDATE card_authorizations SET status = 'CAPTURED', captured_amount = ?, capture_entry_id = ?,
                           captured_at = now() WHERE id = ?
                    """, capture, entry.journalEntryId(), authId);
            return settlement(authId);
        }));
    }

    public IdempotencyService.Result<SettlementDto> refund(UUID merchantUserId, UUID key, String fingerprint,
                                                          UUID authId, BigDecimal amount) {
        cards.requireEnabled();
        rateLimiter.acquire(SETTLE_LIMIT, merchantUserId);
        return tx.execute(st -> idempotency.execute(merchantUserId, key, fingerprint, 200, SettlementDto.class, () -> {
            Auth auth = lock(merchantUserId, authId);
            if (!"CAPTURED".equals(auth.status())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Only a captured payment can be refunded");
            }
            // An open dispute holds back its amount (F-CARD-4), so the same money can't come back twice.
            BigDecimal remaining = auth.captured().subtract(auth.refunded()).subtract(disputes.openDisputed(authId));
            BigDecimal refund = amount == null ? remaining : Money.normalize(amount);
            if (refund.signum() <= 0) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Nothing is left to refund outside an open dispute");
            }
            if (refund.compareTo(remaining) > 0) {
                throw ApiException.fieldError(org.springframework.http.HttpStatus.BAD_REQUEST, "amount",
                        "can't be more than what is left to refund (" + Money.toJson(remaining) + ")");
            }
            PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.REFUND, "Refund · " + auth.merchant(), null)
                    .debit(SystemAccount.CARD_CLEARING.id(), refund, null)
                    .credit(auth.accountId(), refund, auth.merchant())
                    .build());
            jdbc.update("INSERT INTO card_refunds (authorization_id, amount, journal_entry_id) VALUES (?, ?, ?)",
                    authId, refund, entry.journalEntryId());
            BigDecimal refunded = auth.refunded().add(refund);
            jdbc.update("UPDATE card_authorizations SET refunded_amount = ?, status = ? WHERE id = ?", refunded,
                    refunded.compareTo(auth.captured()) == 0 ? "REFUNDED" : "CAPTURED", authId);
            return settlement(authId);
        }));
    }

    /** Only the user who ran the checkout can settle it; anyone else gets 404. Row-locked against double settles. */
    private Auth lock(UUID merchantUserId, UUID authId) {
        return jdbc.query("""
                SELECT a.id, a.card_id, c.account_id, a.hold_id, a.merchant, a.amount, a.captured_amount,
                       a.refunded_amount, a.status
                  FROM card_authorizations a JOIN cards c ON c.id = a.card_id
                 WHERE a.id = ? AND a.merchant_user_id = ? AND a.status <> 'DECLINED'
                   FOR UPDATE OF a
                """, (rs, i) -> new Auth(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("hold_id", UUID.class), rs.getString("merchant"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("captured_amount"), rs.getBigDecimal("refunded_amount"),
                rs.getString("status")), authId, merchantUserId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Authorization"));
    }

    private SettlementDto settlement(UUID authId) {
        return jdbc.queryForObject("SELECT id, status, amount, captured_amount, refunded_amount FROM card_authorizations "
                + "WHERE id = ?", (rs, i) -> new SettlementDto(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getBigDecimal("amount"), rs.getBigDecimal("captured_amount"), rs.getBigDecimal("refunded_amount")),
                authId);
    }
}
