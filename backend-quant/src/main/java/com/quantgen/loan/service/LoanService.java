package com.quantgen.loan.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.security.KycService;
import com.quantgen.auth.api.AuthDtos.UserDto;
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
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.loan.api.LoanDtos.AdminLoanDto;
import com.quantgen.loan.api.LoanDtos.Applicant;
import com.quantgen.loan.api.LoanDtos.ApplyRequest;
import com.quantgen.loan.api.LoanDtos.InstallmentDto;
import com.quantgen.loan.api.LoanDtos.LoanDetailDto;
import com.quantgen.loan.api.LoanDtos.LoanDto;
import com.quantgen.loan.api.LoanDtos.QuoteDto;
import com.quantgen.loan.api.LoanDtos.QuoteRequest;
import com.quantgen.loan.api.LoanDtos.RepayRequest;
import com.quantgen.loan.api.LoanDtos.RepaymentDto;
import com.quantgen.loan.domain.Loan;
import com.quantgen.loan.domain.LoanInstallment;
import com.quantgen.loan.domain.LoanInstallmentRepository;
import com.quantgen.loan.domain.LoanRepayment;
import com.quantgen.loan.domain.LoanRepaymentRepository;
import com.quantgen.loan.domain.LoanRepository;
import com.quantgen.loan.domain.LoanStatus;
import com.quantgen.loan.domain.RepaymentSource;

/**
 * Loans (F-LOAN-1..6). Lock order, everywhere: idempotency key → loan row ({@code FOR UPDATE}) → ledger
 * rows (inside {@link LedgerService#post}). Every state change of one loan is serialized on its row.
 */
@Service
public class LoanService {

    public static final BigDecimal ANNUAL_RATE = new BigDecimal("12.0000");
    static final BigDecimal MAX_EMI_TO_INCOME = new BigDecimal("0.50");
    static final BigDecimal MAX_AMOUNT_TO_INCOME = BigDecimal.valueOf(20);
    /** Loans whose EMI counts against the customer's income: everything not finished or turned down. */
    private static final Set<LoanStatus> EMI_BEARING = EnumSet.of(LoanStatus.PENDING, LoanStatus.APPROVED, LoanStatus.ACTIVE);
    private static final String LOAN_LABEL = "QuantGen loan";

    private final LoanRepository loans;
    private final LoanInstallmentRepository installments;
    private final LoanRepaymentRepository repayments;
    private final AccountService accounts;
    private final UserService users;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final AdvisoryLocks locks;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;
    private final KycService kyc;

    public LoanService(LoanRepository loans, LoanInstallmentRepository installments, LoanRepaymentRepository repayments,
                       AccountService accounts, UserService users, LedgerService ledger, IdempotencyService idempotency,
                       AdvisoryLocks locks, NotificationService notifications, AuditService audit, Clock clock,
                       KycService kyc) {
        this.loans = loans;
        this.installments = installments;
        this.repayments = repayments;
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.kyc = kyc;
    }

    // ------------------------------------------------------------------ quote and apply

    /** F-LOAN-2/3: the EMI and the eligibility verdict, counting the customer's existing EMIs. */
    @Transactional(readOnly = true)
    public QuoteDto quote(UUID userId, QuoteRequest req) {
        EmiCalculator.Quote q = EmiCalculator.quote(req.amount(), ANNUAL_RATE, req.tenureMonths());
        String reason = ineligibility(userId, req.amount(), q.emi(), req.monthlyIncome());
        return new QuoteDto(q.emi(), q.totalInterest(), q.totalPayable(), reason == null, reason);
    }

    @Transactional
    public LoanDto apply(UUID userId, ApplyRequest req) {
        AccountRef account = accounts.requireOwned(userId, req.disburseAccountId());
        if (account.status() == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "disburseAccountId", "account is closed");
        }
        // F-SEC-6: large loans need KYC on file.
        if (req.amount().compareTo(KycService.LOAN_KYC_THRESHOLD) > 0) {
            kyc.requireVerified(userId);
        }
        // Serialize this customer's applications so two at once can't both pass the EMI-to-income rule.
        locks.lockForTransaction("loans:" + userId);
        BigDecimal amount = Money.normalize(req.amount());
        BigDecimal income = Money.normalize(req.monthlyIncome());
        BigDecimal emi = EmiCalculator.emi(amount, ANNUAL_RATE, req.tenureMonths());
        String reason = ineligibility(userId, amount, emi, income);
        if (reason != null) {
            throw new ApiException(ErrorCode.LOAN_NOT_ELIGIBLE, reason);
        }
        Loan loan = loans.saveAndFlush(new Loan(userId, account.id(), amount, req.tenureMonths(), ANNUAL_RATE, emi,
                income, req.purpose().trim()));
        audit.record(userId, "LOAN_APPLIED", "LOAN", loan.getId().toString(),
                "amount=" + Money.toJson(amount) + " tenureMonths=" + req.tenureMonths());
        return toDto(loan, null);
    }

    /** Null if eligible, otherwise a customer-facing reason. */
    private String ineligibility(UUID userId, BigDecimal amount, BigDecimal emi, BigDecimal income) {
        BigDecimal maxAmount = income.multiply(MAX_AMOUNT_TO_INCOME);
        if (amount.compareTo(maxAmount) > 0) {
            return "The amount can be at most 20 times your monthly income (" + Money.toJson(maxAmount) + ")";
        }
        BigDecimal existing = loans.sumEmiByUserAndStatusIn(userId, EMI_BEARING);
        BigDecimal maxTotalEmi = income.multiply(MAX_EMI_TO_INCOME);
        BigDecimal total = existing.add(emi);
        if (total.compareTo(maxTotalEmi) > 0) {
            BigDecimal pct = total.multiply(BigDecimal.valueOf(100)).divide(income, 1, RoundingMode.HALF_UP);
            return "Total EMIs would be " + pct.toPlainString() + "% of your monthly income; the limit is 50%"
                    + (existing.signum() > 0 ? " (existing EMIs: " + Money.toJson(existing) + ")" : "");
        }
        return null;
    }

    // ------------------------------------------------------------------ customer reads

    @Transactional(readOnly = true)
    public List<LoanDto> list(UUID userId) {
        List<Loan> owned = loans.findByUserIdOrderByCreatedAtDesc(userId);
        Map<UUID, LocalDate> next = nextDueDates(owned);
        return owned.stream().map(l -> toDto(l, next.get(l.getId()))).toList();
    }

    @Transactional(readOnly = true)
    public LoanDetailDto get(UUID userId, UUID loanId) {
        Loan loan = loans.findByIdAndUserId(loanId, userId).orElseThrow(() -> ApiException.notFound("Loan"));
        List<LoanInstallment> schedule = installments.findByLoanIdOrderByInstallmentNoAsc(loanId);
        return new LoanDetailDto(loan.getId(), loan.getAmount(), loan.getTenureMonths(), loan.getAnnualRate(),
                loan.getEmi(), loan.getStatus(), loan.getOutstandingPrincipal(), nextDue(loan, schedule),
                loan.getCreatedAt(), loan.getPurpose(), loan.getDecisionReason(),
                schedule.stream().map(LoanService::toDto).toList());
    }

    // ------------------------------------------------------------------ repayment

    /** F-LOAN-5, idempotent. Without an amount, pays the next due instalment in full. */
    @Transactional
    public IdempotencyService.Result<RepaymentDto> repay(UUID userId, UUID loanId, UUID idempotencyKey,
                                                         String fingerprint, RepayRequest req) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, RepaymentDto.class,
                () -> doRepay(userId, loanId, req));
    }

    private RepaymentDto doRepay(UUID userId, UUID loanId, RepayRequest req) {
        // Lock first, then check ownership: loading the row unlocked beforehand would leave a stale copy in
        // the persistence context that the locking query returns as-is.
        Loan loan = loans.lockById(loanId)
                .filter(l -> l.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Loan"));
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only an active loan can be repaid");
        }
        AccountRef account = accounts.requireOwned(userId, req.accountId());
        if (account.status() == AccountStatus.FROZEN) {
            throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot repay loans");
        }
        if (account.status() != AccountStatus.ACTIVE) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Account is closed");
        }
        List<LoanInstallment> unpaid = unpaid(loanId);
        if (unpaid.isEmpty()) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Nothing is due on this loan");
        }
        BigDecimal totalDue = sumRemaining(unpaid);
        BigDecimal amount = req.amount() != null ? Money.normalize(req.amount()) : unpaid.getFirst().remaining();
        if (amount.compareTo(totalDue) > 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "amount",
                    "must be at most " + Money.toJson(totalDue) + " (everything still due on this loan)");
        }
        return applyRepayment(loan, unpaid, account, amount, RepaymentSource.MANUAL, userId);
    }

    /**
     * Allocates {@code amount} to the oldest dues first (interest, then principal, instalment by instalment),
     * posts customer → LOAN_RECEIVABLE (principal) + INTEREST_INCOME (interest), and closes the loan when
     * everything is paid. Caller holds the loan lock and has checked {@code amount ≤ sum(unpaid)}.
     */
    private RepaymentDto applyRepayment(Loan loan, List<LoanInstallment> unpaid, AccountRef account, BigDecimal amount,
                                        RepaymentSource source, UUID actorId) {
        Instant now = clock.instant();
        BigDecimal left = amount;
        BigDecimal principalPaid = BigDecimal.ZERO;
        BigDecimal interestPaid = BigDecimal.ZERO;
        for (LoanInstallment inst : unpaid) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal towardsInterest = left.min(inst.remainingInterest());
            BigDecimal towardsPrincipal = left.subtract(towardsInterest).min(inst.remainingPrincipal());
            inst.pay(towardsInterest, towardsPrincipal, now);
            left = left.subtract(towardsInterest).subtract(towardsPrincipal);
            interestPaid = interestPaid.add(towardsInterest);
            principalPaid = principalPaid.add(towardsPrincipal);
        }
        if (left.signum() != 0) {
            throw new IllegalStateException("Repayment exceeds the amount due");
        }

        JournalRequest.Builder journal = JournalRequest.builder(JournalType.LOAN_REPAYMENT,
                source == RepaymentSource.AUTO_DEBIT ? "EMI auto-debit" : "Loan repayment", actorId)
                .debit(account.id(), amount, LOAN_LABEL);
        if (principalPaid.signum() > 0) {
            journal.credit(SystemAccount.LOAN_RECEIVABLE.id(), principalPaid, null);
        }
        if (interestPaid.signum() > 0) {
            journal.credit(SystemAccount.INTEREST_INCOME.id(), interestPaid, null);
        }
        PostedEntry entry = ledger.post(journal.build()); // INSUFFICIENT_FUNDS / ACCOUNT_FROZEN roll everything back

        if (principalPaid.signum() > 0) {
            loan.applyPrincipal(principalPaid);
        }
        boolean closed = unpaid.stream().allMatch(LoanInstallment::isPaid);
        if (closed) {
            loan.close(now);
        }
        installments.flush();
        loans.flush();
        LoanRepayment repayment = repayments.saveAndFlush(new LoanRepayment(loan.getId(), account.id(), principalPaid,
                interestPaid, source, entry.journalEntryId()));

        String body = Money.toJson(amount) + " INR was applied to your loan from account "
                + AccountNumbers.mask(account.accountNumber()) + "."
                + (closed ? " The loan is now fully repaid and closed." : "");
        notifications.notify(loan.getUserId(), NotificationType.EMI_PAID, closed ? "Loan closed" : "EMI paid", body);
        return new RepaymentDto(repayment.getId(), loan.getId(), repayment.getAmount(), principalPaid, interestPaid,
                repayment.getCreatedAt());
    }

    // ------------------------------------------------------------------ admin decisions (F-ADM-3)

    @Transactional
    public LoanDto approve(UUID adminId, UUID loanId, String reason) {
        Loan loan = lockPending(adminId, loanId);
        AccountRef account = accounts.findById(loan.getDisburseAccountId()).orElseThrow();
        Instant now = clock.instant();
        String note = blankToNull(reason);

        PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.LOAN_DISBURSAL, "Loan disbursal", adminId)
                .debit(SystemAccount.LOAN_RECEIVABLE.id(), loan.getAmount(), null)
                .credit(account.id(), loan.getAmount(), LOAN_LABEL)
                .build());
        loan.activate(adminId, note, entry.journalEntryId(), now);

        LocalDate firstDue = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).plusMonths(1);
        List<LoanInstallment> schedule = EmiCalculator.schedule(loan.getAmount(), loan.getAnnualRate(),
                        loan.getTenureMonths(), firstDue).stream()
                .map(r -> new LoanInstallment(loan.getId(), r.installmentNo(), r.dueDate(), r.emi(), r.principal(),
                        r.interest()))
                .toList();
        installments.saveAll(schedule);
        loans.flush();

        audit.record(adminId, "LOAN_APPROVED", "LOAN", loan.getId().toString(), note != null ? note : "Approved");
        notifications.notify(loan.getUserId(), NotificationType.LOAN_APPROVED, "Loan approved",
                "Your loan of " + Money.toJson(loan.getAmount()) + " INR was approved and credited to account "
                        + AccountNumbers.mask(account.accountNumber()) + ". First EMI of "
                        + Money.toJson(loan.getEmi()) + " INR is due on " + firstDue + ".");
        return toDto(loan, firstDue);
    }

    @Transactional
    public LoanDto reject(UUID adminId, UUID loanId, String reason) {
        Loan loan = lockPending(adminId, loanId);
        String note = reason.trim();
        loan.reject(adminId, note, clock.instant());
        loans.flush();
        audit.record(adminId, "LOAN_REJECTED", "LOAN", loan.getId().toString(), note);
        notifications.notify(loan.getUserId(), NotificationType.LOAN_REJECTED, "Loan application declined",
                "Your application for " + Money.toJson(loan.getAmount()) + " INR was declined. Reason: " + note);
        return toDto(loan, null);
    }

    private Loan lockPending(UUID adminId, UUID loanId) {
        Loan loan = loans.lockById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        if (loan.getStatus() != LoanStatus.PENDING) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only a pending loan can be approved or rejected");
        }
        if (loan.getUserId().equals(adminId)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "You cannot decide on your own loan application");
        }
        return loan;
    }

    // ------------------------------------------------------------------ admin reads

    @Transactional(readOnly = true)
    public PageDto<AdminLoanDto> adminList(LoanStatus status, Pageable pageable) {
        Page<Loan> page = status == null
                ? loans.findAllByOrderByCreatedAtDescIdDesc(pageable)
                : loans.findByStatusOrderByCreatedAtDescIdDesc(status, pageable);
        Map<UUID, UserDto> applicants = users.findUsers(page.getContent().stream().map(Loan::getUserId)
                .collect(Collectors.toSet()));
        Map<UUID, LocalDate> next = nextDueDates(page.getContent());
        return PageDto.of(page, l -> {
            UserDto u = applicants.get(l.getUserId());
            return new AdminLoanDto(l.getId(), l.getAmount(), l.getTenureMonths(), l.getAnnualRate(), l.getEmi(),
                    l.getStatus(), l.getOutstandingPrincipal(), next.get(l.getId()), l.getCreatedAt(),
                    new Applicant(l.getUserId(), u == null ? null : u.fullName(), u == null ? null : u.email()),
                    l.getMonthlyIncome(), l.getPurpose(), l.getDecisionReason());
        });
    }

    @Transactional(readOnly = true)
    public long countByStatus(LoanStatus status) {
        return loans.countByStatus(status);
    }

    @Transactional(readOnly = true)
    public BigDecimal outstandingPrincipal() {
        return loans.sumOutstandingByStatus(LoanStatus.ACTIVE);
    }

    // ------------------------------------------------------------------ daily job steps (F-LOAN-6)

    /** Ids of active loans with an unpaid instalment due on or before {@code date}. */
    @Transactional(readOnly = true)
    public List<UUID> loansWithDues(LocalDate date) {
        return installments.findLoanIdsWithDuesOnOrBefore(date);
    }

    /**
     * Auto-debits the oldest unpaid instalment due on or before {@code date} from the loan's disbursal
     * account, in its own transaction. Returns false when nothing (more) is due. Throws INSUFFICIENT_FUNDS
     * or ACCOUNT_FROZEN (rolled back) when it can't be paid.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean autoDebitNext(UUID loanId, LocalDate date) {
        Loan loan = loans.lockById(loanId).orElseThrow();
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            return false;
        }
        List<LoanInstallment> unpaid = unpaid(loanId);
        if (unpaid.isEmpty() || unpaid.getFirst().getDueDate().isAfter(date)) {
            return false;
        }
        AccountRef account = accounts.findById(loan.getDisburseAccountId()).orElseThrow();
        applyRepayment(loan, unpaid, account, unpaid.getFirst().remaining(), RepaymentSource.AUTO_DEBIT, null);
        return true;
    }

    /** Marks unpaid instalments due on or before {@code date} OVERDUE, notifying once per instalment. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int markOverdue(UUID loanId, LocalDate date) {
        Loan loan = loans.lockById(loanId).orElseThrow();
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            return 0;
        }
        int changed = 0;
        for (LoanInstallment inst : unpaid(loanId)) {
            if (!inst.getDueDate().isAfter(date) && inst.markOverdue()) {
                changed++;
                notifications.notify(loan.getUserId(), NotificationType.EMI_OVERDUE, "EMI overdue",
                        "EMI #" + inst.getInstallmentNo() + " of " + Money.toJson(inst.remaining())
                                + " INR due on " + inst.getDueDate()
                                + " could not be auto-debited. Add money and repay it from the loan page.");
            }
        }
        installments.flush();
        return changed;
    }

    /** Sends one EMI_DUE reminder per PENDING instalment due on {@code dueDate}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int sendReminders(LocalDate dueDate) {
        List<LoanInstallment> due = installments.findNeedingReminder(dueDate);
        Instant now = clock.instant();
        for (LoanInstallment inst : due) {
            Loan loan = loans.findById(inst.getLoanId()).orElseThrow();
            String account = accounts.findById(loan.getDisburseAccountId())
                    .map(a -> AccountNumbers.mask(a.accountNumber())).orElse("your account");
            notifications.notify(loan.getUserId(), NotificationType.EMI_DUE, "EMI due soon",
                    "EMI #" + inst.getInstallmentNo() + " of " + Money.toJson(inst.remaining()) + " INR is due on "
                            + inst.getDueDate() + ". It will be auto-debited from account " + account + ".");
            inst.markReminderSent(now);
        }
        installments.flush();
        return due.size();
    }

    // ------------------------------------------------------------------ helpers

    @Transactional(readOnly = true)
    public List<LoanDto> loansOfUser(UUID userId) {
        return list(userId);
    }

    private List<LoanInstallment> unpaid(UUID loanId) {
        return installments.findByLoanIdOrderByInstallmentNoAsc(loanId).stream().filter(i -> !i.isPaid()).toList();
    }

    private static BigDecimal sumRemaining(List<LoanInstallment> list) {
        return list.stream().map(LoanInstallment::remaining).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<UUID, LocalDate> nextDueDates(List<Loan> list) {
        List<UUID> active = list.stream().filter(l -> l.getStatus() == LoanStatus.ACTIVE).map(Loan::getId).toList();
        if (active.isEmpty()) {
            return Map.of();
        }
        return installments.findByLoanIdInOrderByLoanIdAscInstallmentNoAsc(active).stream()
                .filter(i -> !i.isPaid())
                .collect(Collectors.toMap(LoanInstallment::getLoanId, LoanInstallment::getDueDate, (a, b) -> a));
    }

    private static LocalDate nextDue(Loan loan, List<LoanInstallment> schedule) {
        if (loan.getStatus() != LoanStatus.ACTIVE) {
            return null;
        }
        return schedule.stream().filter(i -> !i.isPaid()).findFirst().map(LoanInstallment::getDueDate).orElse(null);
    }

    static LoanDto toDto(Loan l, LocalDate nextDueDate) {
        return new LoanDto(l.getId(), l.getAmount(), l.getTenureMonths(), l.getAnnualRate(), l.getEmi(), l.getStatus(),
                l.getOutstandingPrincipal(), nextDueDate, l.getCreatedAt());
    }

    static InstallmentDto toDto(LoanInstallment i) {
        return new InstallmentDto(i.getInstallmentNo(), i.getDueDate(), i.getEmi(), i.getPrincipal(), i.getInterest(),
                i.remaining(), i.getStatus(), i.getPaidAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
