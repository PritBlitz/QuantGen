package com.quantgen.savings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
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
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.SystemAccount;

/**
 * F-SAV-3 fixed deposits. Booking moves the principal from an everyday account into a new FIXED_DEPOSIT account
 * (D-037), at the published slab rate for the tenure. Interest is simple interest per day held,
 * {@code principal × rate × days / 36500}, rounded half-even to the paisa, and is paid from
 * {@code INTEREST_EXPENSE} only when the term ends: at maturity (paid out, or added to the principal of a renewed
 * term on the same account) or on premature closure (slab rate for the days actually held, minus 1%).
 * An FD's balance never changes in between, so it can't be spent, topped up or transferred.
 */
@Service
public class FixedDepositService {

    private static final Logger log = LoggerFactory.getLogger(FixedDepositService.class);

    public static final int MIN_DAYS = 7;
    public static final int MAX_DAYS = 1825;
    public static final int MAX_ACTIVE = 20;
    static final BigDecimal PREMATURE_PENALTY = new BigDecimal("1.00");

    public enum OnMaturity { PAYOUT, RENEW }

    public record RateDto(int minDays, int maxDays, BigDecimal annualRate) {
    }

    public record FixedDepositDto(UUID id, String accountNumberMasked, BigDecimal principal, BigDecimal annualRate,
                                  int tenureDays, LocalDate startDate, LocalDate maturityDate,
                                  BigDecimal accruedInterest, BigDecimal maturityAmount, OnMaturity onMaturity,
                                  String status) {
    }

    private record Fd(UUID id, UUID userId, UUID accountId, UUID payoutAccountId, String accountNumber,
                      BigDecimal principal, BigDecimal annualRate, int tenureDays, LocalDate startDate,
                      LocalDate maturityDate, OnMaturity onMaturity, String status, BigDecimal interestPaid) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final StepUpService stepUp;
    private final AdvisoryLocks locks;
    private final NotificationService notifications;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public FixedDepositService(JdbcTemplate jdbc, AccountService accounts, LedgerService ledger,
                               IdempotencyService idempotency, StepUpService stepUp, AdvisoryLocks locks,
                               NotificationService notifications, AuditService audit, Clock clock,
                               PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.stepUp = stepUp;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ rates and maths

    @Transactional(readOnly = true)
    public List<RateDto> rates() {
        return jdbc.query("SELECT min_days, max_days, annual_rate FROM fixed_deposit_rates ORDER BY min_days",
                (rs, i) -> new RateDto(rs.getInt(1), rs.getInt(2), rs.getBigDecimal(3)));
    }

    /** The slab rate for a tenure, or zero below the shortest slab. */
    BigDecimal rateFor(int days) {
        return jdbc.query("SELECT annual_rate FROM fixed_deposit_rates WHERE ? BETWEEN min_days AND max_days",
                (rs, i) -> rs.getBigDecimal(1), days).stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /** Simple interest for {@code days}, half-even to the paisa. */
    static BigDecimal interest(BigDecimal principal, BigDecimal annualRate, long days) {
        if (days <= 0 || annualRate.signum() <= 0) {
            return Money.roundToPaise(BigDecimal.ZERO);
        }
        return Money.roundToPaise(principal.multiply(annualRate).multiply(BigDecimal.valueOf(days))
                .divide(BigDecimal.valueOf(36500), 10, RoundingMode.HALF_EVEN));
    }

    /** Full-term interest of a deposit (used by the cash-flow forecast). */
    public static BigDecimal interestFor(BigDecimal principal, BigDecimal annualRate, int tenureDays) {
        return interest(principal, annualRate, tenureDays);
    }

    /** Premature closure: the slab rate for the days held minus 1% (never below zero); nothing under 7 days. */
    BigDecimal prematureRate(long daysHeld) {
        if (daysHeld < MIN_DAYS) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal r = rateFor((int) Math.min(daysHeld, MAX_DAYS)).subtract(PREMATURE_PENALTY);
        return r.signum() < 0 ? BigDecimal.ZERO.setScale(2) : r;
    }

    // ------------------------------------------------------------------ booking

    @Transactional
    public IdempotencyService.Result<FixedDepositDto> book(UUID userId, UUID key, String fingerprint,
                                                            UUID fromAccountId, BigDecimal rawAmount, int tenureDays,
                                                            OnMaturity onMaturity) {
        return idempotency.execute(userId, key, fingerprint, 201, FixedDepositDto.class, () -> {
            if (tenureDays < MIN_DAYS || tenureDays > MAX_DAYS) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "tenureDays",
                        "must be between " + MIN_DAYS + " and " + MAX_DAYS + " days");
            }
            AccountRef from = accounts.requireOwned(userId, fromAccountId);
            if (!from.isEveryday()) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId",
                        "book from a savings or checking account");
            }
            locks.lockForTransaction("fixed-deposits:" + userId);
            Integer active = jdbc.queryForObject("SELECT count(*) FROM fixed_deposits WHERE user_id = ? "
                    + "AND status = 'ACTIVE'", Integer.class, userId);
            if (active != null && active >= MAX_ACTIVE) {
                throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can have at most " + MAX_ACTIVE
                        + " active fixed deposits");
            }
            BigDecimal amount = Money.normalize(rawAmount);
            BigDecimal rate = rateFor(tenureDays);
            LocalDate start = today();
            AccountRef fd = accounts.openFixedDepositAccount(userId);
            ledger.post(JournalRequest.builder(JournalType.TRANSFER, "Fixed deposit booked", userId)
                    .debit(from.id(), amount, "To fixed deposit " + AccountNumbers.mask(fd.accountNumber()))
                    .credit(fd.id(), amount, "From " + AccountNumbers.mask(from.accountNumber()))
                    .build());
            UUID id = jdbc.queryForObject("""
                    INSERT INTO fixed_deposits (user_id, account_id, payout_account_id, principal, annual_rate,
                                                tenure_days, start_date, maturity_date, on_maturity)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, userId, fd.id(), from.id(), amount, rate, tenureDays, Date.valueOf(start),
                    Date.valueOf(start.plusDays(tenureDays)), onMaturity.name());
            audit.record(userId, "FD_BOOKED", "FIXED_DEPOSIT", id.toString(),
                    "amount=" + Money.toJson(amount) + " tenureDays=" + tenureDays + " rate=" + rate);
            return toDto(find(id).orElseThrow());
        });
    }

    @Transactional(readOnly = true)
    public List<FixedDepositDto> list(UUID userId) {
        return jdbc.query(SELECT + " WHERE f.user_id = ? ORDER BY f.created_at DESC", (rs, i) -> fd(rs), userId)
                .stream().map(this::toDto).toList();
    }

    // ------------------------------------------------------------------ premature closure

    @Transactional
    public IdempotencyService.Result<FixedDepositDto> close(UUID userId, UUID key, String fingerprint, UUID id,
                                                             String stepUpToken) {
        return idempotency.execute(userId, key, fingerprint, 200, FixedDepositDto.class, () -> {
            Fd fd = lock(id).filter(f -> f.userId().equals(userId))
                    .orElseThrow(() -> ApiException.notFound("Fixed deposit"));
            if (!"ACTIVE".equals(fd.status())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This fixed deposit has already ended");
            }
            stepUp.require(userId, StepUpAction.FD_CLOSE, stepUpToken);
            long held = ChronoUnit.DAYS.between(fd.startDate(), today());
            BigDecimal interest = interest(fd.principal(), prematureRate(held), held);
            payOut(fd, interest, "Fixed deposit closed early");
            finish(fd, "CLOSED", interest);
            audit.record(userId, "FD_CLOSED", "FIXED_DEPOSIT", id.toString(),
                    "daysHeld=" + held + " interest=" + Money.toJson(interest));
            return toDto(find(id).orElseThrow());
        });
    }

    // ------------------------------------------------------------------ maturity

    /** Ends every ACTIVE term due on or before {@code today}, each in its own transaction; returns how many. */
    public int matureDue(LocalDate today) {
        List<UUID> due = jdbc.queryForList("SELECT id FROM fixed_deposits WHERE status = 'ACTIVE' "
                + "AND maturity_date <= ? ORDER BY maturity_date, id", UUID.class, Date.valueOf(today));
        int done = 0;
        for (UUID id : due) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> matureOne(id, today)))) {
                    done++;
                }
            } catch (RuntimeException e) {
                // E.g. the FD account was frozen by an admin: leave it ACTIVE and try again tomorrow.
                log.warn("Fixed deposit {} could not mature today: {}", id, e.toString());
            }
        }
        return done;
    }

    private boolean matureOne(UUID id, LocalDate today) {
        Fd fd = lockSkipLocked(id).orElse(null);
        if (fd == null || !"ACTIVE".equals(fd.status()) || fd.maturityDate().isAfter(today)) {
            return false;
        }
        BigDecimal interest = interest(fd.principal(), fd.annualRate(), fd.tenureDays());
        String masked = AccountNumbers.mask(fd.accountNumber());
        if (fd.onMaturity() == OnMaturity.RENEW) {
            postInterest(fd, interest);
            finish(fd, "MATURED", interest);
            BigDecimal principal = fd.principal().add(interest);
            BigDecimal rate = rateFor(fd.tenureDays());
            jdbc.update("""
                    INSERT INTO fixed_deposits (user_id, account_id, payout_account_id, principal, annual_rate,
                                                tenure_days, start_date, maturity_date, on_maturity, renewed_from)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'RENEW', ?)
                    """, fd.userId(), fd.accountId(), fd.payoutAccountId(), principal, rate, fd.tenureDays(),
                    Date.valueOf(fd.maturityDate()), Date.valueOf(fd.maturityDate().plusDays(fd.tenureDays())), fd.id());
            notifications.notify(fd.userId(), NotificationType.FD_MATURED, "Fixed deposit renewed",
                    "Your fixed deposit " + masked + " earned ₹" + Money.toJson(interest) + " and was renewed for ₹"
                            + Money.toJson(principal) + " at " + rate.toPlainString() + "% p.a.");
        } else {
            payOut(fd, interest, "Fixed deposit matured");
            finish(fd, "MATURED", interest);
            notifications.notify(fd.userId(), NotificationType.FD_MATURED, "Fixed deposit matured",
                    "Your fixed deposit " + masked + " matured: ₹" + Money.toJson(fd.principal().add(interest))
                            + " was paid into your account.");
        }
        return true;
    }

    // ------------------------------------------------------------------ helpers

    private void postInterest(Fd fd, BigDecimal interest) {
        if (interest.signum() > 0) {
            ledger.post(JournalRequest.builder(JournalType.INTEREST, "Fixed deposit interest", fd.userId())
                    .debit(SystemAccount.INTEREST_EXPENSE.id(), interest, null)
                    .credit(fd.accountId(), interest, "Interest at " + fd.annualRate().toPlainString() + "% p.a.")
                    .build());
        }
    }

    /** Interest into the FD, then everything back to the payout account, then the emptied FD account is closed. */
    private void payOut(Fd fd, BigDecimal interest, String description) {
        postInterest(fd, interest);
        BigDecimal total = fd.principal().add(interest);
        AccountRef payout = accounts.findById(fd.payoutAccountId()).orElseThrow();
        ledger.post(JournalRequest.builder(JournalType.TRANSFER, description, fd.userId())
                .debit(fd.accountId(), total, "To " + AccountNumbers.mask(payout.accountNumber()))
                .credit(payout.id(), total, "From fixed deposit " + AccountNumbers.mask(fd.accountNumber()))
                .build());
        accounts.closeEmptyAccount(fd.accountId());
    }

    private void finish(Fd fd, String status, BigDecimal interest) {
        jdbc.update("UPDATE fixed_deposits SET status = ?, interest_paid = ?, closed_at = ? WHERE id = ?",
                status, interest, Timestamp.from(clock.instant()), fd.id());
    }

    private FixedDepositDto toDto(Fd f) {
        boolean active = "ACTIVE".equals(f.status());
        BigDecimal accrued = active
                ? interest(f.principal(), f.annualRate(),
                Math.min(ChronoUnit.DAYS.between(f.startDate(), today()), f.tenureDays()))
                : f.interestPaid();
        BigDecimal atMaturity = active ? interest(f.principal(), f.annualRate(), f.tenureDays()) : f.interestPaid();
        return new FixedDepositDto(f.id(), AccountNumbers.mask(f.accountNumber()), f.principal(), f.annualRate(),
                f.tenureDays(), f.startDate(), f.maturityDate(), accrued, f.principal().add(atMaturity),
                f.onMaturity(), f.status());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }

    private static final String SELECT = """
            SELECT f.*, a.account_number FROM fixed_deposits f JOIN accounts a ON a.id = f.account_id""";

    private java.util.Optional<Fd> find(UUID id) {
        return jdbc.query(SELECT + " WHERE f.id = ?", (rs, i) -> fd(rs), id).stream().findFirst();
    }

    private java.util.Optional<Fd> lock(UUID id) {
        return jdbc.query(SELECT + " WHERE f.id = ? FOR UPDATE OF f", (rs, i) -> fd(rs), id).stream().findFirst();
    }

    private java.util.Optional<Fd> lockSkipLocked(UUID id) {
        return jdbc.query(SELECT + " WHERE f.id = ? FOR UPDATE OF f SKIP LOCKED", (rs, i) -> fd(rs), id).stream()
                .findFirst();
    }

    private static Fd fd(ResultSet rs) throws SQLException {
        return new Fd(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("account_id", UUID.class), rs.getObject("payout_account_id", UUID.class),
                rs.getString("account_number"), rs.getBigDecimal("principal"), rs.getBigDecimal("annual_rate"),
                rs.getInt("tenure_days"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("maturity_date").toLocalDate(), OnMaturity.valueOf(rs.getString("on_maturity")),
                rs.getString("status"), rs.getBigDecimal("interest_paid"));
    }
}
