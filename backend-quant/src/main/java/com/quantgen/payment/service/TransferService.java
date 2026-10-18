package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.account.service.TransactionCategorizer;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.metrics.QgMetrics;
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
import com.quantgen.payment.domain.Beneficiary;
import com.quantgen.payment.domain.BeneficiaryRepository;
import com.quantgen.payment.domain.Transfer;
import com.quantgen.payment.domain.TransferRepository;
import com.quantgen.payment.domain.TransferSource;

/** F-PAY-1..3: transfers to own accounts or to any account number, idempotent, with limits. */
@Service
public class TransferService {

    public static final BigDecimal DAILY_OUTGOING_LIMIT = LimitsService.DAILY_MAX;
    /** F-SEC-2: external transfers above this need a step-up token. */
    public static final BigDecimal STEP_UP_THRESHOLD = new BigDecimal("50000.00");
    /** F-SEC-5: at most this much to a payee during the first 24 h after it is saved. */
    public static final BigDecimal NEW_PAYEE_CAP = new BigDecimal("25000.00");
    static final Duration NEW_PAYEE_WINDOW = Duration.ofHours(24);
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
    private final QgMetrics metrics;
    private final LimitsService limits;
    private final StepUpService stepUp;
    private final BeneficiaryRepository beneficiaries;
    private final HandleService handles;
    private final TransactionCategorizer categorizer;

    public TransferService(TransferRepository transfers, AccountService accounts, UserService users,
                           LedgerService ledger, IdempotencyService idempotency, AdvisoryLocks locks,
                           NotificationService notifications, AuditService audit, Clock clock,
                           QgMetrics metrics, LimitsService limits, StepUpService stepUp,
                           BeneficiaryRepository beneficiaries, HandleService handles,
                           TransactionCategorizer categorizer) {
        this.transfers = transfers;
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.metrics = metrics;
        this.limits = limits;
        this.stepUp = stepUp;
        this.beneficiaries = beneficiaries;
        this.handles = handles;
        this.categorizer = categorizer;
    }

    @Transactional
    public IdempotencyService.Result<TransferDto> transfer(UUID userId, UUID idempotencyKey, String fingerprint,
                                                           TransferRequest req, String stepUpToken) {
        // The step-up check runs inside the action, after the idempotency claim: a rejected attempt rolls the
        // claim back (so the retry with the same key works), and a replay of a finished request needs no step-up.
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, TransferDto.class, () -> {
            Target target = resolveTarget(req.toAccountNumber(), req.toHandle());
            return execute(userId, req.fromAccountId(), target, req.amount(), req.note(), TransferSource.MANUAL,
                    stepUpToken, false);
        });
    }

    /** Where a transfer goes, plus the request field to blame if it can't receive money. */
    public record Target(AccountRef account, String field) {
    }

    /** Exactly one of an account number and a handle (PRD 12.4); 400 on the named field if it doesn't exist. */
    public Target resolveTarget(String toAccountNumber, String toHandle) {
        boolean byNumber = toAccountNumber != null && !toAccountNumber.isBlank();
        boolean byHandle = toHandle != null && !toHandle.isBlank();
        if (byNumber == byHandle) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "toAccountNumber",
                    "give exactly one of toAccountNumber or toHandle");
        }
        if (byHandle) {
            return new Target(handles.accountFor(toHandle).orElseThrow(() -> ApiException.fieldError(
                    HttpStatus.BAD_REQUEST, "toHandle", "no account with this handle")), "toHandle");
        }
        return new Target(accounts.findByNumber(toAccountNumber).orElseThrow(() -> ApiException.fieldError(
                HttpStatus.BAD_REQUEST, "toAccountNumber", "no account with this number")), "toAccountNumber");
    }

    /**
     * One transfer with every check (limits, new-payee cooling, step-up), inside the caller's transaction and
     * idempotency claim. {@code preAuthorized} skips the step-up prompt for runs nobody is present for (scheduled
     * transfers, whose creation was stepped up instead); limits and cooling still apply.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TransferDto execute(UUID userId, UUID fromAccountId, Target target, BigDecimal rawAmount, String rawNote,
                               TransferSource source, String stepUpToken, boolean preAuthorized) {
        AccountRef from = accounts.requireOwned(userId, fromAccountId);
        AccountRef to = target.account();
        if (to.id().equals(from.id())) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(),
                    "must be different from the source account");
        }
        if (to.status() == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(), "this account cannot receive money");
        }
        if (from.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot send money");
        }
        BigDecimal amount = Money.normalize(rawAmount);
        boolean internal = to.userId().equals(userId);
        // D-037: goals and fixed deposits deal only with their owner's accounts, in both directions.
        if (!internal && !from.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId", "goals and fixed deposits can only move money to your own accounts");
        }
        if (!internal && !to.isEveryday()) {
            // Same answer as an unknown number or handle, so someone else's goal or FD can't be probed for.
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(),
                    target.field().equals("toHandle") ? "no account with this handle" : "no account with this number");
        }
        if (from.type() == AccountType.FIXED_DEPOSIT) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId",
                    "a fixed deposit pays out when it matures or is closed");
        }
        if (to.type() == AccountType.FIXED_DEPOSIT) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, target.field(), "this account cannot receive money");
        }

        if (!internal) {
            // Serialize this user's outgoing transfers so concurrent requests can't overshoot the daily limit.
            locks.lockForTransaction("transfers:" + userId);
            LimitsService.Effective chosen = limits.effective(userId);
            if (amount.compareTo(chosen.perTransfer()) > 0) {
                throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                        "Per-transfer limit of " + Money.toJson(chosen.perTransfer()) + " exceeded");
            }
            Instant startOfDay = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                    .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
            BigDecimal sentToday = transfers.sumOutgoingSince(userId, startOfDay);
            if (sentToday.add(amount).compareTo(chosen.daily()) > 0) {
                BigDecimal remaining = chosen.daily().subtract(sentToday).max(BigDecimal.ZERO);
                throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                        "Daily transfer limit of " + Money.toJson(chosen.daily()) + " exceeded; remaining today: "
                                + Money.toJson(remaining));
            }

            // F-SEC-5 new-payee cooling, then F-SEC-2 step-up. Cooling is a hard stop, so it comes first.
            Optional<Beneficiary> payee = beneficiaries.findByUserIdAndAccountNumber(userId, to.accountNumber());
            Instant now = clock.instant();
            boolean newPayee = payee.isPresent() && payee.get().getCreatedAt().isAfter(now.minus(NEW_PAYEE_WINDOW));
            boolean firstToNewPayee = false;
            if (newPayee) {
                BigDecimal sentToPayee = transfers.sumToAccountSince(userId, to.accountNumber(),
                        payee.get().getCreatedAt());
                if (sentToPayee.add(amount).compareTo(NEW_PAYEE_CAP) > 0) {
                    BigDecimal left = NEW_PAYEE_CAP.subtract(sentToPayee).max(BigDecimal.ZERO);
                    throw new ApiException(ErrorCode.COOLING_PERIOD, "This payee was added less than 24 hours ago; "
                            + "you can send at most " + Money.toJson(NEW_PAYEE_CAP) + " to it until then. Remaining: "
                            + Money.toJson(left));
                }
                firstToNewPayee = sentToPayee.signum() == 0;
            }
            if (!preAuthorized && (amount.compareTo(STEP_UP_THRESHOLD) > 0 || firstToNewPayee)) {
                stepUp.require(userId, StepUpAction.TRANSFER, stepUpToken);
            }
        }

        String fromName = users.getUser(from.userId()).fullName();
        String toName = users.getUser(to.userId()).fullName();
        String note = blankToNull(rawNote);
        String description = note != null ? note : (internal ? "Transfer between own accounts" : "Fund transfer");

        PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.TRANSFER, description, userId)
                .debit(from.id(), amount, "To " + toName + " " + AccountNumbers.mask(to.accountNumber()))
                .credit(to.id(), amount, "From " + fromName + " " + AccountNumbers.mask(from.accountNumber()))
                .build());

        Transfer transfer = transfers.saveAndFlush(new Transfer(userId, from.id(), to.id(), from.accountNumber(),
                to.accountNumber(), toName, amount, note, internal, entry.journalEntryId(), source));

        metrics.transferCompleted(internal);
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
        return toDto(transfer, categorizer.categoriesOfEntries(userId, List.of(transfer.getJournalEntryId()))
                .get(transfer.getJournalEntryId()));
    }

    @Transactional(readOnly = true)
    public PageDto<TransferDto> list(UUID userId, Pageable pageable) {
        Page<Transfer> page = transfers.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable);
        Map<UUID, String> cats = categorizer.categoriesOfEntries(userId,
                page.getContent().stream().map(Transfer::getJournalEntryId).filter(java.util.Objects::nonNull).toList());
        return PageDto.of(page, t -> toDto(t, cats.get(t.getJournalEntryId())));
    }

    /** {@code category} is the sender's category for the transfer (PRD 12.2). */
    public static TransferDto toDto(Transfer t, String category) {
        return new TransferDto(t.getId(), t.getFromAccountNumber(), t.getToAccountNumber(), t.getToName(),
                t.getAmount(), t.getNote(), t.getStatus(), t.getCreatedAt(), t.getSource(), category);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
