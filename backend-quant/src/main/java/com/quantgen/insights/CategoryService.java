package com.quantgen.insights;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.service.TransactionCategorizer;
import com.quantgen.analytics.LedgerKinds;
import com.quantgen.common.error.ApiException;

/**
 * F-INS-1 category engine. A transaction's category is, in order: the category its owner picked for it; the one
 * they picked for its counterparty ("always use this for payments to X"); otherwise a deterministic rule on the
 * kind of money movement ({@link LedgerKinds}) and, for payments to other people, keywords in the note. Nothing is
 * stored unless the user overrides it, so rule changes apply to history too. The optional model classifier of
 * D-020 is not used: notes that no keyword matches stay {@code TRANSFERS}.
 */
@Service
public class CategoryService implements TransactionCategorizer {

    public static final List<String> KEYS = List.of("FOOD", "GROCERIES", "SHOPPING", "TRANSPORT", "BILLS", "RENT",
            "ENTERTAINMENT", "HEALTH", "EDUCATION", "TRAVEL", "TRANSFERS", "INVESTMENTS", "LOANS", "INCOME", "OTHER");

    /**
     * Note keywords for payments to other people, first match wins. Postgres regular expressions ({@code \m} and
     * {@code \M} are word boundaries); constants only, never user input.
     */
    static final Map<String, String> KEYWORDS = keywords();

    private static Map<String, String> keywords() {
        Map<String, String> k = new LinkedHashMap<>();
        k.put("RENT", "\\mrent\\M|landlord|\\mpg\\M|hostel|maintenance");
        k.put("GROCERIES", "grocer|vegetable|sabzi|kirana|bigbasket|blinkit|zepto|\\mmilk\\M|fruits");
        k.put("FOOD", "\\mfood|lunch|dinner|breakfast|zomato|swiggy|restaurant|\\mcafe|coffee|pizza|biryani|snack|chai");
        k.put("TRANSPORT", "\\muber\\M|\\mola\\M|rapido|\\mcab\\M|taxi|\\mauto\\M|metro|fuel|petrol|diesel|parking");
        k.put("TRAVEL", "flight|\\mtrain\\M|hotel|\\mtrip\\M|travel|irctc|holiday|vacation");
        k.put("SHOPPING", "amazon|flipkart|myntra|shopping|clothes|shoes|gift");
        k.put("ENTERTAINMENT", "movie|netflix|spotify|concert|\\mgame|party|cinema|tickets");
        k.put("HEALTH", "doctor|medicine|pharmacy|hospital|clinic|\\mgym\\M|medical|dentist");
        k.put("EDUCATION", "tuition|school|college|course|\\mbooks?\\M|\\mfees?\\M|exam");
        k.put("BILLS", "electricity|recharge|wifi|broadband|\\mbills?\\M");
        return java.util.Collections.unmodifiableMap(k);
    }

    /** SQL expression for the rule-based category of a {@code cl} row (no overrides). */
    static String ruleSql() {
        StringBuilder notes = new StringBuilder("CASE");
        KEYWORDS.forEach((cat, re) -> notes.append(" WHEN cl.description ~* '").append(re).append("' THEN '")
                .append(cat).append("'"));
        notes.append(" ELSE 'TRANSFERS' END");
        return """
                CASE
                  WHEN cl.kind IN ('DEPOSIT', 'TOPUP', 'INTEREST', 'TRANSFER_IN') THEN 'INCOME'
                  WHEN cl.kind = 'BILL_PAYMENT' THEN 'BILLS'
                  WHEN cl.kind IN ('LOAN_REPAYMENT', 'BORROWED') THEN 'LOANS'
                  WHEN cl.kind IN ('INVESTED', 'DIVESTED') THEN 'INVESTMENTS'
                  WHEN cl.kind = 'TRANSFER_OUT' THEN %s
                  ELSE 'TRANSFERS'
                END""".formatted(notes);
    }

    /**
     * A CTE named {@code cat}: every {@code cl} column plus {@code category}. Expects {@code cl} to be defined
     * before it in the same WITH clause.
     */
    public static String catCte() {
        return """
                cat AS (
                  SELECT cl.*, COALESCE(tc.category,
                           CASE WHEN cl.kind IN ('TRANSFER_OUT', 'TRANSFER_IN') THEN co.category END,
                           %s) AS category
                    FROM cl
                    LEFT JOIN transaction_categories tc ON tc.line_id = cl.line_id
                    LEFT JOIN category_overrides co ON co.user_id = cl.user_id
                                                   AND co.counterparty_account_id = cl.other_account
                )
                """.formatted(ruleSql());
    }

    private static final Instant BEGINNING = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant END = Instant.parse("2200-01-01T00:00:00Z");

    private final NamedParameterJdbcTemplate named;

    public CategoryService(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    public static String label(String key) {
        return switch (key) {
            case "FOOD" -> "Food and dining";
            case "BILLS" -> "Bills and utilities";
            default -> key.charAt(0) + key.substring(1).toLowerCase(java.util.Locale.ROOT);
        };
    }

    public static void requireKey(String field, String category) {
        if (category == null || !KEYS.contains(category)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field, "must be one of " + String.join(", ", KEYS));
        }
    }

    // ------------------------------------------------------------------ lookups

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> categoriesOfLines(UUID userId, Collection<UUID> lineIds) {
        if (lineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        named.query("WITH " + LedgerKinds.cl(true, "         AND l.id IN (:ids)\n") + ", " + catCte()
                        + " SELECT line_id, category FROM cat",
                allTime(userId).addValue("ids", lineIds),
                rs -> {
                    out.put(rs.getObject("line_id", UUID.class), rs.getString("category"));
                });
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> categoriesOfEntries(UUID userId, Collection<UUID> journalEntryIds) {
        if (journalEntryIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        // odir DESC puts 'DEBIT' before 'CREDIT', so for a move between own accounts the sending side wins.
        named.query("WITH " + LedgerKinds.cl(true, "         AND l.journal_entry_id IN (:ids)\n") + ", " + catCte()
                        + " SELECT journal_entry_id, category FROM cat ORDER BY journal_entry_id, odir DESC",
                allTime(userId).addValue("ids", journalEntryIds),
                rs -> {
                    out.putIfAbsent(rs.getObject("journal_entry_id", UUID.class), rs.getString("category"));
                });
        return out;
    }

    /** Line facts needed to recategorize: its owner, kind and counterparty account. Empty if not the user's. */
    record LineFacts(UUID lineId, String kind, UUID otherAccount, UUID otherUser) {
    }

    java.util.Optional<LineFacts> lineFacts(UUID userId, UUID lineId) {
        return named.query("WITH " + LedgerKinds.cl(true, "         AND l.id = :id\n") + """
                        SELECT cl.line_id, cl.kind, cl.other_account, oa.user_id AS other_user
                          FROM cl LEFT JOIN accounts oa ON oa.id = cl.other_account
                        """, allTime(userId).addValue("id", lineId),
                (rs, i) -> new LineFacts(rs.getObject("line_id", UUID.class), rs.getString("kind"),
                        rs.getObject("other_account", UUID.class), rs.getObject("other_user", UUID.class)))
                .stream().findFirst();
    }

    static MapSqlParameterSource allTime(UUID userId) {
        return new MapSqlParameterSource().addValue("uid", userId)
                .addValue("lo", Timestamp.from(BEGINNING)).addValue("hi", Timestamp.from(END));
    }
}
