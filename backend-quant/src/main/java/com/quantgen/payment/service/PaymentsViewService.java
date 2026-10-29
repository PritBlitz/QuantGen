package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.web.PageDto;

/**
 * PRD §12.4 "Payments view": one newest-first list of the caller's own payments across transfers, bills, top-ups,
 * card payments and refunds, each with its receipt number. Read-only. Every branch is scoped to the caller; an
 * incoming transfer shows only once COMPLETED, so a receiver never learns of a held or rejected one.
 */
@Service
public class PaymentsViewService {

    public record PaymentDto(String id, UUID sourceId, String receiptNo, String kind, String direction,
                             BigDecimal amount, String status, UUID accountId, String accountNumberMasked,
                             String counterparty, String reference, Instant createdAt, Instant completedAt) {
    }

    static final Set<String> KINDS = Set.of("TRANSFER", "BILL", "TOPUP", "CARD", "REFUND");
    static final Set<String> STATUSES = Set.of("COMPLETED", "FAILED", "PENDING_REVIEW", "REJECTED", "CREATED",
            "CREDITED", "EXPIRED", "AUTHORIZED", "DECLINED", "CAPTURED", "REFUNDED");
    private static final Pattern RECEIPT = Pattern.compile("^QG\\d{13,18}$");
    private static final int MAX_Q = 50;

    /** Six branches, each taking the caller's id once. Counterparty text is the same as account history's. */
    private static final String PAYMENTS = """
            SELECT 'TRANSFER' AS kind, 'OUT' AS direction, t.id AS source_id, t.receipt_no, t.amount, t.status,
                   t.from_account_id AS account_id, t.from_account_number AS account_number,
                   COALESCE(l.counterparty, 'To ' || t.to_name || ' ••••' || right(t.to_account_number, 4)) AS counterparty,
                   NULL AS reference, t.created_at, je.created_at AS completed_at
              FROM transfers t
              LEFT JOIN journal_entries je ON je.id = t.journal_entry_id
              LEFT JOIN ledger_lines l ON l.journal_entry_id = t.journal_entry_id AND l.ledger_account_id = t.from_account_id
             WHERE t.user_id = ?
            UNION ALL
            SELECT 'TRANSFER', 'IN', t.id, t.receipt_no, t.amount, t.status, t.to_account_id, t.to_account_number,
                   l.counterparty, NULL, t.created_at, je.created_at
              FROM transfers t
              JOIN accounts a ON a.id = t.to_account_id
              JOIN journal_entries je ON je.id = t.journal_entry_id
              JOIN ledger_lines l ON l.journal_entry_id = t.journal_entry_id AND l.ledger_account_id = t.to_account_id
             WHERE a.user_id = ? AND t.user_id <> a.user_id AND t.status = 'COMPLETED'
            UNION ALL
            SELECT 'BILL', 'OUT', p.id, p.receipt_no, p.amount, 'COMPLETED', p.from_account_id, a.account_number,
                   b.name, NULL, p.created_at, p.created_at
              FROM bill_payments p
              JOIN billers b ON b.id = p.biller_id
              JOIN accounts a ON a.id = p.from_account_id
             WHERE p.user_id = ?
            UNION ALL
            SELECT 'TOPUP', 'IN', p.id, p.receipt_no, p.amount, p.status, p.account_id, a.account_number,
                   'Razorpay', COALESCE(p.gateway_payment_id, p.gateway_order_id), p.created_at, je.created_at
              FROM topups p
              JOIN accounts a ON a.id = p.account_id
              LEFT JOIN journal_entries je ON je.id = p.journal_entry_id
             WHERE p.user_id = ?
            UNION ALL
            SELECT 'CARD', 'OUT', ca.id, ca.receipt_no,
                   CASE WHEN ca.captured_amount > 0 THEN ca.captured_amount ELSE ca.amount END, ca.status,
                   c.account_id, a.account_number, ca.merchant, ca.merchant, ca.created_at, ca.captured_at
              FROM card_authorizations ca
              JOIN cards c ON c.id = ca.card_id
              JOIN accounts a ON a.id = c.account_id
             WHERE c.user_id = ?
            UNION ALL
            SELECT 'REFUND', 'IN', ca.id, r.receipt_no, r.amount, 'COMPLETED', c.account_id, a.account_number,
                   ca.merchant, ca.merchant, r.created_at, r.created_at
              FROM card_refunds r
              JOIN card_authorizations ca ON ca.id = r.authorization_id
              JOIN cards c ON c.id = ca.card_id
              JOIN accounts a ON a.id = c.account_id
             WHERE c.user_id = ?
            """;
    private static final int BRANCHES = 6;

    private final JdbcTemplate jdbc;

    public PaymentsViewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageDto<PaymentDto> list(UUID userId, LocalDate from, LocalDate to, String kind, String status, String q,
                                    Pageable pageable) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must be on or before 'to'");
        }
        String k = kind == null || kind.isBlank() ? null : kind.trim().toUpperCase(Locale.ROOT);
        if (k != null && !KINDS.contains(k)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "kind", "must be one of " + KINDS);
        }
        String st = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (st != null && !STATUSES.contains(st)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "is not a payment status");
        }
        String query = q == null || q.isBlank() ? null : q.trim();
        if (query != null && query.length() > MAX_Q) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "q", "must be at most " + MAX_Q + " characters");
        }

        StringBuilder where = new StringBuilder(" WHERE TRUE");
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < BRANCHES; i++) {
            args.add(userId);
        }
        if (from != null) {
            where.append(" AND p.created_at >= ?");
            args.add(Timestamp.from(from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        }
        if (to != null) {
            where.append(" AND p.created_at < ?");
            args.add(Timestamp.from(to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        }
        if (k != null) {
            where.append(" AND p.kind = ?");
            args.add(k);
        }
        if (st != null) {
            where.append(" AND p.status = ?");
            args.add(st);
        }
        if (query != null && RECEIPT.matcher(query.toUpperCase(Locale.ROOT)).matches()) {
            where.append(" AND p.receipt_no = ?");
            args.add(query.toUpperCase(Locale.ROOT));
        } else if (query != null) {
            where.append(" AND p.counterparty ILIKE ? ESCAPE '\\'");
            args.add("%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String base = "FROM (" + PAYMENTS + ") p" + where;
        Long total = jdbc.queryForObject("SELECT count(*) " + base, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        List<PaymentDto> content = jdbc.query("SELECT p.* " + base
                + " ORDER BY p.created_at DESC, p.receipt_no DESC, p.direction LIMIT ? OFFSET ?",
                (rs, i) -> dto(rs), pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        int size = pageable.getPageSize();
        return new PageDto<>(content, pageable.getPageNumber(), size, totalElements,
                (int) ((totalElements + size - 1) / size));
    }

    /** The caller's side of one payment; someone else's receipt number is 404. */
    @Transactional(readOnly = true)
    public PaymentDto get(UUID userId, String receiptNo) {
        String r = receiptNo == null ? "" : receiptNo.trim().toUpperCase(Locale.ROOT);
        if (!RECEIPT.matcher(r).matches()) {
            throw ApiException.notFound("Payment");
        }
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < BRANCHES; i++) {
            args.add(userId);
        }
        args.add(r);
        return jdbc.query("SELECT p.* FROM (" + PAYMENTS + ") p WHERE p.receipt_no = ? ORDER BY p.direction DESC LIMIT 1",
                (rs, i) -> dto(rs), args.toArray()).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("Payment"));
    }

    private static PaymentDto dto(ResultSet rs) throws SQLException {
        String receipt = rs.getString("receipt_no");
        String direction = rs.getString("direction");
        Timestamp completed = rs.getTimestamp("completed_at");
        return new PaymentDto(receipt + ":" + direction, rs.getObject("source_id", UUID.class), receipt,
                rs.getString("kind"), direction, rs.getBigDecimal("amount"), rs.getString("status"),
                rs.getObject("account_id", UUID.class), AccountNumbers.mask(rs.getString("account_number")),
                rs.getString("counterparty"), rs.getString("reference"), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }
}
