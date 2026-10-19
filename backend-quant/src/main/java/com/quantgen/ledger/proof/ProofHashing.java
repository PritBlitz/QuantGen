package com.quantgen.ledger.proof;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The canonical encodings of Proof of Balance (PRD 12.10). The browser recomputes the Merkle part
 * ({@code frontend-quant/src/lib/merkle.ts}), so every string here must stay byte-for-byte stable: changing one
 * breaks every published proof and the whole seal chain.
 */
public final class ProofHashing {

    /** The prev_hash of seal position 1. */
    public static final String GENESIS_HASH = "0".repeat(64);
    public static final String ZERO = "0.0000";

    private static final DateTimeFormatter UTC_MICROS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private ProofHashing() {
    }

    /** Lowercase hex SHA-256 of the UTF-8 bytes of {@code text}. */
    public static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A plain decimal string with exactly 4 fraction digits ("1250.5000"). Fails rather than round. */
    public static String amount(BigDecimal value) {
        return value.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    }

    // ------------------------------------------------------------------ Merkle sum tree

    public record Node(String hash, BigDecimal sum) {

        public String sumString() {
            return amount(sum);
        }
    }

    /** {@code SHA256("<accountId>|<balance>|<salt>")}, sum = balance. */
    public static Node leaf(UUID accountId, BigDecimal balance, String salt) {
        return new Node(sha256(accountId + "|" + amount(balance) + "|" + salt), balance.setScale(4));
    }

    /** The zero-sum padding leaf at global leaf index {@code index}: {@code SHA256("pad-<index>|0.0000|pad")}. */
    public static Node pad(int index) {
        return new Node(sha256("pad-" + index + "|" + ZERO + "|pad"), BigDecimal.ZERO.setScale(4));
    }

    /** {@code SHA256("<left.hash>|<left.sum>|<right.hash>|<right.sum>")}, sum = left.sum + right.sum. */
    public static Node parent(Node left, Node right) {
        return new Node(sha256(left.hash() + "|" + left.sumString() + "|" + right.hash() + "|" + right.sumString()),
                left.sum().add(right.sum()));
    }

    /** The smallest power of two that is at least {@code max(count, 1)}. */
    public static int paddedSize(int count) {
        int size = 1;
        while (size < count) {
            size <<= 1;
        }
        return size;
    }

    // ------------------------------------------------------------------ seal chain

    public record EntryData(UUID id, String type, UUID reversesEntryId, UUID createdBy, Instant createdAt,
                            String description) {
    }

    public record LineData(UUID id, UUID ledgerAccountId, String direction, BigDecimal amount,
                           BigDecimal balanceAfter, String counterparty) {
    }

    /**
     * The canonical form of one journal entry and its lines (ARCHITECTURE 12.10): one header line, then one line per
     * ledger line ordered by line id, joined by "\n". Free text (description, counterparty) enters as its SHA-256 so
     * no separator can be smuggled in. Timestamps are UTC with microseconds (Postgres precision).
     * <pre>
     * &lt;entryId&gt;|&lt;type&gt;|&lt;reversesEntryId or empty&gt;|&lt;createdBy or empty&gt;|&lt;createdAt&gt;|&lt;sha256(description)&gt;
     * &lt;lineId&gt;|&lt;ledgerAccountId&gt;|&lt;DEBIT|CREDIT&gt;|&lt;amount&gt;|&lt;balanceAfter&gt;|&lt;sha256(counterparty or empty)&gt;
     * </pre>
     */
    public static String canonical(EntryData entry, List<LineData> lines) {
        String header = entry.id() + "|" + entry.type() + "|" + orEmpty(entry.reversesEntryId()) + "|"
                + orEmpty(entry.createdBy()) + "|" + UTC_MICROS.format(entry.createdAt()) + "|"
                + sha256(entry.description());
        String body = lines.stream()
                .sorted(Comparator.comparing(l -> l.id().toString()))
                .map(l -> l.id() + "|" + l.ledgerAccountId() + "|" + l.direction() + "|" + amount(l.amount()) + "|"
                        + amount(l.balanceAfter()) + "|" + sha256(l.counterparty() == null ? "" : l.counterparty()))
                .collect(Collectors.joining("\n"));
        return header + "\n" + body;
    }

    /** {@code SHA256("<prevHash>|<canonical>")}. */
    public static String sealHash(String prevHash, String canonical) {
        return sha256(prevHash + "|" + canonical);
    }

    private static String orEmpty(UUID id) {
        return id == null ? "" : id.toString();
    }
}
