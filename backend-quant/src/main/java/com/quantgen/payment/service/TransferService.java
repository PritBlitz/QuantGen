package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
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
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.api.PaymentDtos.TransferRequest;
import com.quantgen.payment.domain.Transfer;
import com.quantgen.payment.domain.TransferRepository;

/** F-PAY-1..3: transfers to own accounts or to any account number, idempotent, with limits. */
@Service
public class TransferService {

    public static final BigDecimal DAILY_OUTGOING_LIMIT = new BigDecimal("500000.00");
    static final BigDecimal LARGE_TRANSFER_AUDIT_THRESHOLD = new BigDecimal("100000.00");

    private final TransferRepository transfers;
    private final AccountService accounts;
    private final UserService users;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AdvisoryLocks locks;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;

    public TransferService(TransferRepository transfers, AccountService accounts, UserService users,
                           LedgerService ledger, IdempotencyService idempotency, AdvisoryLocks locks,
                           NotificationService notifications, AuditService audit, Clock clock) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IdempotencyService.Result<TransferDto> transfer(UUID userId, UUID idempotencyKey, String fingerprint,
                                                           TransferRequest req) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, TransferDto.class,
                () -> doTransfer(userId, req));
    }

    private TransferDto doTransfer(UUID userId, TransferRequest req) {
        AccountRef from = accounts.requireOwned(userId, req.fromAccountId());
        AccountRef to = accounts.findByNumber(req.toAccountNumber())
                .orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST, "toAccountNumber",
                        "no account with this number"));
        if (to.id().equals(from.id())) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "toAccountNumber",
                    "must be different from the source account");
        }
        if (to.status() == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "toAccountNumber", "this account cannot receive money");
        }
        if (from.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot send money");
        }
        BigDecimal amount = Money.normalize(req.amount());
        boolean internal = to.userId().equals(userId);

        if (!internal) {
            // Serialize this user's outgoing transfers so concurrent requests can't overshoot the daily limit.
            locks.lockForTransaction("transfers:" + userId);
            Instant startOfDay = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                    .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
            BigDecimal sentToday = transfers.sumOutgoingSince(userId, startOfDay);
            if (sentToday.add(amount).compareTo(DAILY_OUTGOING_LIMIT) > 0) {
                BigDecimal remaining = DAILY_OUTGOING_LIMIT.subtract(sentToday).max(BigDecimal.ZERO);
                throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                        "Daily transfer limit of 500000.00 exceeded; remaining today: " + Money.toJson(remaining));
            }
        }

        String fromName = users.getUser(from.userId()).fullName();
        String toName = users.getUser(to.userId()).fullName();
        String note = blankToNull(req.note());
        String description = note != null ? note : (internal ? "Transfer between own accounts" : "Fund transfer");

        PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.TRANSFER, description, userId)
                .debit(from.id(), amount, "To " + toName + " " + AccountNumbers.mask(to.accountNumber()))
                .credit(to.id(), amount, "From " + fromName + " " + AccountNumbers.mask(from.accountNumber()))
                .build());

        Transfer transfer = transfers.saveAndFlush(new Transfer(userId, from.id(), to.id(), from.accountNumber(),
                to.accountNumber(), toName, amount, note, internal, entry.journalEntryId()));

        if (!internal) {
            notifications.notify(to.userId(), NotificationType.MONEY_RECEIVED, "Money received",
                    "You received " + Money.toJson(amount) + " INR from " + fromName + " in account "
                            + AccountNumbers.mask(to.accountNumber()) + ".");
        }
        if (amount.compareTo(LARGE_TRANSFER_AUDIT_THRESHOLD) >= 0) {
            audit.record(userId, "LARGE_TRANSFER", "TRANSFER", transfer.getId().toString(),
                    "amount=" + Money.toJson(amount) + " from=" + AccountNumbers.mask(from.accountNumber())
                            + " to=" + AccountNumbers.mask(to.accountNumber()));
        }
        return toDto(transfer);
    }

    @Transactional(readOnly = true)
    public PageDto<TransferDto> list(UUID userId, Pageable pageable) {
        return PageDto.of(transfers.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable), TransferService::toDto);
    }

    static TransferDto toDto(Transfer t) {
        return new TransferDto(t.getId(), t.getFromAccountNumber(), t.getToAccountNumber(), t.getToName(),
                t.getAmount(), t.getNote(), t.getStatus(), t.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
