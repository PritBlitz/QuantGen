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
import com.quantgen.common.config.TimeConfig;
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

    public record BillerDto(UUID id, String name, String category, String consumerNumberLabel,
                            String consumerNumberPattern) {
    }

    public record BillDto(UUID billId, BigDecimal amount, LocalDate dueDate, String customerNameMasked) {
    }

    public record BillPaymentDto(UUID id, UUID billerId, String billerName, String category,
                                 String consumerNumberMasked, BigDecimal amount, UUID fromAccountId,
                                 UUID transactionId, Instant createdAt) {
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

    public BillService(JdbcTemplate jdbc, AccountService accounts, UserService users, LedgerService ledger,
                       IdempotencyService idempotency, UserRateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
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
        // The bill for a month is due on the 20th; after that, next month's bill is the one to pay.
        YearMonth cycle = today.getDayOfMonth() <= DUE_DAY ? YearMonth.from(today) : YearMonth.from(today).plusMonths(1);
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
            SELECT p.id, p.biller_id, b.name, b.category, p.consumer_number, p.amount, p.from_account_id,
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
                rs.getObject("transaction_id", UUID.class), rs.getTimestamp("created_at").toInstant());
    }
}
