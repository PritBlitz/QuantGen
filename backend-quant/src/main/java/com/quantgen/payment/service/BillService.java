package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
import com.quantgen.common.security.UserRateLimiter;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.risk.RiskService;

/**
 * F-PAY-11 bill payments to simulated billers. "Fetching" a bill returns a stable simulated amount for
 * (biller, consumer number, month), and paying it posts customer → {@code BILLER_CLEARING} through the ledger.
 * A bill is paid at most once; a fetched bill can be paid for 24 hours, after which it has to be fetched again.
 */
@Service
public class BillService {

    static final Duration BILL_VALIDITY = Duration.ofHours(24);
    static final int DUE_DAY = 20;
    static final UserRateLimiter.Limit FETCH_LIMIT = new UserRateLimiter.Limit("bill-fetch", 30, Duration.ofMinutes(1));
    public static final int MAX_SAVED = 20;

    public record SavedBillerDto(UUID id, UUID billerId, String billerName, String category, String consumerNumberLabel,
                                 String consumerNumberMasked, String nickname, Instant createdAt) {
    }

    /** A saved biller with its full consumer number, for paying it (never returned to the client). */
    public record SavedBiller(UUID id, BillerDto biller, String consumerNumber, String nickname) {
    }

    public record BillerDto(UUID id, String name, String category, String consumerNumberLabel,
                            String consumerNumberPattern) {
    }

    public record BillDto(UUID billId, BigDecimal amount, LocalDate dueDate, String customerNameMasked) {
    }

    public record BillPaymentDto(UUID id, UUID billerId, String billerName, String category,
                                 String consumerNumberMasked, BigDecimal amount, UUID fromAccountId,
                                 UUID transactionId, Instant createdAt, String receiptNo) {
    }

    private record Bill(UUID id, UUID userId, BillerDto biller, String consumerNumber, BigDecimal amount,
                        Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final UserService users;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;
    private final AdvisoryLocks locks;
    private final LimitsService limits;
    private final RiskService risk;
    private final AuditService audit;

    public BillService(JdbcTemplate jdbc, AccountService accounts, UserService users, LedgerService ledger,
                       IdempotencyService idempotency, UserRateLimiter rateLimiter, Clock clock, AdvisoryLocks locks,
                       LimitsService limits, RiskService risk, AuditService audit) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.locks = locks;
        this.limits = limits;
        this.risk = risk;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<BillerDto> billers(String category) {
        return category == null
                ? jdbc.query("SELECT * FROM billers ORDER BY category, name", (rs, i) -> biller(rs))
                : jdbc.query("SELECT * FROM billers WHERE category = ? ORDER BY name", (rs, i) -> biller(rs), category);
    }

    @Transactional
    public BillDto fetch(UUID userId, UUID billerId, String rawConsumerNumber) {
        BillerDto biller = findBiller(billerId).orElseThrow(() -> ApiException.notFound("Biller"));
        String consumerNumber = rawConsumerNumber == null ? "" : rawConsumerNumber.trim();
        if (!Pattern.compile(biller.consumerNumberPattern()).matcher(consumerNumber).matches()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "consumerNumber",
                    "is not a valid " + biller.consumerNumberLabel().toLowerCase(java.util.Locale.ROOT));
        }
        rateLimiter.acquire(FETCH_LIMIT, userId);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        YearMonth cycle = cycle(today);
        Instant expiresAt = clock.instant().plus(BILL_VALIDITY);
        BigDecimal amount = simulatedAmount(billerId, consumerNumber, cycle);
        // One row per cycle: fetching again refreshes the validity window of an unpaid bill.
        UUID id = jdbc.queryForObject("""
                INSERT INTO bills (user_id, biller_id, consumer_number, billing_month, amount, due_date,
                                   customer_name_masked, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, biller_id, consumer_number, billing_month)
                DO UPDATE SET expires_at = EXCLUDED.expires_at
                RETURNING id
                """, UUID.class, userId, billerId, consumerNumber, cycle.toString(), amount,
                Date.valueOf(cycle.atDay(DUE_DAY)), HandleService.maskName(users.getUser(userId).fullName()),
                Timestamp.from(expiresAt));
        Boolean paid = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bill_payments WHERE bill_id = ?)",
                Boolean.class, id);
        if (Boolean.TRUE.equals(paid)) {
            throw new ApiException(ErrorCode.INVALID_STATE, "This month's bill is already paid");
        }
        return jdbc.queryForObject("SELECT id, amount, due_date, customer_name_masked FROM bills WHERE id = ?",
                (rs, i) -> new BillDto(rs.getObject("id", UUID.class), rs.getBigDecimal("amount"),
                        rs.getDate("due_date").toLocalDate(), rs.getString("customer_name_masked")), id);
    }

    @Transactional
    public IdempotencyService.Result<BillPaymentDto> pay(UUID userId, UUID idempotencyKey, String fingerprint,
                                                         UUID billId, UUID fromAccountId) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, BillPaymentDto.class, () -> {
            Bill bill = lockBill(billId).filter(b -> b.userId().equals(userId))
                    .orElseThrow(() -> ApiException.notFound("Bill"));
            Boolean paid = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM bill_payments WHERE bill_id = ?)",
                    Boolean.class, billId);
            if (Boolean.TRUE.equals(paid)) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This bill is already paid");
            }
            if (!clock.instant().isBefore(bill.expiresAt())) {
                throw new ApiException(ErrorCode.INVALID_STATE, "This bill has expired; fetch it again");
            }
            AccountRef from = accounts.requireOwned(userId, fromAccountId);
            if (!from.isEveryday()) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fromAccountId",
                        "pay bills from a savings or checking account");
            }
            // Same per-user lock as transfers and card payments: the daily limit covers all three.
            locks.lockForTransaction("transfers:" + userId);
            limits.requireWithinDaily(userId, bill.amount());
            RiskService.Assessment a = risk.scoreBill(userId, bill.amount());
            if (risk.needsReview(a)) {
                // No review queue for bills: stopped, nothing moves, the customer can try again later or contact us.
                audit.recordAlways(userId, "BILL_PAYMENT_STOPPED", "BILL", billId.toString(),
                        "score=" + a.score() + " signals=" + a.signals().stream().map(RiskService.Signal::code).toList());
                throw new ApiException(ErrorCode.SUSPECTED_FRAUD, "This payment looks unusual and was stopped: "
                        + a.topSignal().toLowerCase(java.util.Locale.ROOT));
            }
            String masked = AccountNumbers.mask(bill.consumerNumber());
            PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.BILL_PAYMENT,
                            bill.biller().name() + " bill " + masked, userId)
                    .debit(from.id(), bill.amount(), bill.biller().name())
                    .credit(SystemAccount.BILLER_CLEARING.id(), bill.amount(), null)
                    .build());
            UUID lineId = entry.lineFor(from.id()).lineId();
            UUID id = jdbc.queryForObject("""
                    INSERT INTO bill_payments (user_id, bill_id, biller_id, from_account_id, consumer_number, amount,
                                               journal_entry_id, transaction_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                    """, UUID.class, userId, billId, bill.biller().id(), from.id(), bill.consumerNumber(),
                    bill.amount(), entry.journalEntryId(), lineId);
            return find(id, userId).orElseThrow();
        });
    }

    @Transactional(readOnly = true)
    public PageDto<BillPaymentDto> list(UUID userId, Pageable pageable) {
        Long total = jdbc.queryForObject("SELECT count(*) FROM bill_payments WHERE user_id = ?", Long.class, userId);
        List<BillPaymentDto> content = jdbc.query(PAYMENT_SELECT + " WHERE p.user_id = ? "
                + "ORDER BY p.created_at DESC, p.id DESC LIMIT ? OFFSET ?", (rs, i) -> payment(rs), userId,
                pageable.getPageSize(), pageable.getOffset());
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    // ------------------------------------------------------------------ saved billers

    @Transactional(readOnly = true)
    public List<SavedBillerDto> savedBillers(UUID userId) {
        return jdbc.query(SAVED_SELECT + " WHERE s.user_id = ? ORDER BY s.created_at, s.id",
                (rs, i) -> savedDto(savedRow(rs)), userId);
    }

    @Transactional
    public SavedBillerDto saveBiller(UUID userId, UUID billerId, String rawConsumerNumber, String rawNickname) {
        BillerDto biller = findBiller(billerId).orElseThrow(() -> ApiException.fieldError(HttpStatus.BAD_REQUEST,
                "billerId", "unknown biller"));
        String consumerNumber = rawConsumerNumber == null ? "" : rawConsumerNumber.trim();
        if (!Pattern.compile(biller.consumerNumberPattern()).matcher(consumerNumber).matches()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "consumerNumber",
                    "is not a valid " + biller.consumerNumberLabel().toLowerCase(java.util.Locale.ROOT));
        }
        String nickname = rawNickname == null || rawNickname.isBlank() ? null : rawNickname.strip();
        // Serialize this user's saves so two at once can't pass the cap together.
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        Integer saved = jdbc.queryForObject("SELECT count(*) FROM saved_billers WHERE user_id = ?", Integer.class, userId);
        if (saved != null && saved >= MAX_SAVED) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can save at most " + MAX_SAVED + " billers; remove one first");
        }
        List<UUID> id = jdbc.queryForList("""
                INSERT INTO saved_billers (user_id, biller_id, consumer_number, nickname) VALUES (?, ?, ?, ?)
                ON CONFLICT (user_id, biller_id, consumer_number) DO NOTHING RETURNING id
                """, UUID.class, userId, billerId, consumerNumber, nickname);
        if (id.isEmpty()) {
            throw ApiException.fieldError(HttpStatus.CONFLICT, "consumerNumber", "is already saved for this biller");
        }
        return savedDto(savedRow(userId, id.getFirst()).orElseThrow());
    }

    /** Fetches this cycle's bill for a saved biller (the client never sees its full consumer number). */
    @Transactional
    public BillDto fetchSaved(UUID userId, UUID savedBillerId) {
        SavedBiller saved = savedBiller(userId, savedBillerId).orElseThrow(() -> ApiException.notFound("Saved biller"));
        return fetch(userId, saved.biller().id(), saved.consumerNumber());
    }

    /** Someone else's saved biller is 404, like one that doesn't exist. */
    @Transactional
    public void deleteSavedBiller(UUID userId, UUID id) {
        if (jdbc.update("DELETE FROM saved_billers WHERE id = ? AND user_id = ?", id, userId) == 0) {
            throw ApiException.notFound("Saved biller");
        }
    }

    @Transactional(readOnly = true)
    public Optional<SavedBiller> savedBiller(UUID userId, UUID id) {
        return savedRow(userId, id).map(SavedRow::saved);
    }

    private Optional<SavedRow> savedRow(UUID userId, UUID id) {
        return jdbc.query(SAVED_SELECT + " WHERE s.id = ? AND s.user_id = ?", (rs, i) -> savedRow(rs), id, userId)
                .stream().findFirst();
    }

    /** Whether this user already paid the bill of the cycle that {@code day} falls in. */
    @Transactional(readOnly = true)
    public boolean paidFor(UUID userId, UUID billerId, String consumerNumber, LocalDate day) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM bill_payments p JOIN bills b ON b.id = p.bill_id
                                WHERE b.user_id = ? AND b.biller_id = ? AND b.consumer_number = ? AND b.billing_month = ?)
                """, Boolean.class, userId, billerId, consumerNumber, cycle(day).toString()));
    }

    /** The amount of the bill of the cycle that {@code day} falls in (what fetching it that day returns). */
    public static BigDecimal amountDue(UUID billerId, String consumerNumber, LocalDate day) {
        return simulatedAmount(billerId, consumerNumber, cycle(day));
    }

    /** The bill for a month is due on the 20th; after that, next month's bill is the one to pay. */
    public static YearMonth cycle(LocalDate day) {
        return day.getDayOfMonth() <= DUE_DAY ? YearMonth.from(day) : YearMonth.from(day).plusMonths(1);
    }

    private static final String SAVED_SELECT = """
            SELECT s.id, s.consumer_number, s.nickname, s.created_at, b.id AS biller_id, b.name, b.category,
                   b.consumer_number_label, b.consumer_number_pattern
              FROM saved_billers s JOIN billers b ON b.id = s.biller_id
            """;

    private record SavedRow(SavedBiller saved, Instant createdAt) {
    }

    private static SavedRow savedRow(ResultSet rs) throws SQLException {
        BillerDto biller = new BillerDto(rs.getObject("biller_id", UUID.class), rs.getString("name"),
                rs.getString("category"), rs.getString("consumer_number_label"), rs.getString("consumer_number_pattern"));
        return new SavedRow(new SavedBiller(rs.getObject("id", UUID.class), biller, rs.getString("consumer_number"),
                rs.getString("nickname")), rs.getTimestamp("created_at").toInstant());
    }

    private static SavedBillerDto savedDto(SavedRow r) {
        SavedBiller s = r.saved();
        return new SavedBillerDto(s.id(), s.biller().id(), s.biller().name(), s.biller().category(),
                s.biller().consumerNumberLabel(), AccountNumbers.mask(s.consumerNumber()), s.nickname(), r.createdAt());
    }

    /** ₹200.00 to ₹4,999.99, stable for (biller, consumer number, month) and spread evenly. */
    static BigDecimal simulatedAmount(UUID billerId, String consumerNumber, YearMonth cycle) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest((billerId + ":" + consumerNumber + ":" + cycle).getBytes(StandardCharsets.UTF_8));
            long paise = new BigInteger(1, java.util.Arrays.copyOf(h, 8)).mod(BigInteger.valueOf(480_000)).longValue();
            return BigDecimal.valueOf(20_000 + paise, 2);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String PAYMENT_SELECT = """
            SELECT p.id, p.receipt_no, p.biller_id, b.name, b.category, p.consumer_number, p.amount, p.from_account_id,
                   p.transaction_id, p.created_at
              FROM bill_payments p JOIN billers b ON b.id = p.biller_id
            """;

    private Optional<BillPaymentDto> find(UUID id, UUID userId) {
        return jdbc.query(PAYMENT_SELECT + " WHERE p.id = ? AND p.user_id = ?", (rs, i) -> payment(rs), id, userId)
                .stream().findFirst();
    }

    private Optional<BillerDto> findBiller(UUID id) {
        return jdbc.query("SELECT * FROM billers WHERE id = ?", (rs, i) -> biller(rs), id).stream().findFirst();
    }

    private Optional<Bill> lockBill(UUID id) {
        return jdbc.query("""
                SELECT bl.id, bl.user_id, bl.consumer_number, bl.amount, bl.expires_at, b.*
                  FROM bills bl JOIN billers b ON b.id = bl.biller_id
                 WHERE bl.id = ? FOR UPDATE OF bl
                """, (rs, i) -> new Bill(rs.getObject(1, UUID.class), rs.getObject("user_id", UUID.class),
                new BillerDto(rs.getObject(6, UUID.class), rs.getString("name"), rs.getString("category"),
                        rs.getString("consumer_number_label"), rs.getString("consumer_number_pattern")),
                rs.getString("consumer_number"), rs.getBigDecimal("amount"),
                rs.getTimestamp("expires_at").toInstant()), id).stream().findFirst();
    }

    private static BillerDto biller(ResultSet rs) throws SQLException {
        return new BillerDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("category"),
                rs.getString("consumer_number_label"), rs.getString("consumer_number_pattern"));
    }

    private static BillPaymentDto payment(ResultSet rs) throws SQLException {
        return new BillPaymentDto(rs.getObject("id", UUID.class), rs.getObject("biller_id", UUID.class),
                rs.getString("name"), rs.getString("category"), AccountNumbers.mask(rs.getString("consumer_number")),
                rs.getBigDecimal("amount"), rs.getObject("from_account_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getTimestamp("created_at").toInstant(),
                rs.getString("receipt_no"));
    }
}
