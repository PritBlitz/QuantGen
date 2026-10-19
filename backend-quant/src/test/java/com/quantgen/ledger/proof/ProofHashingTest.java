package com.quantgen.ledger.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.ledger.proof.ProofHashing.EntryData;
import com.quantgen.ledger.proof.ProofHashing.LineData;
import com.quantgen.ledger.proof.ProofHashing.Node;

/**
 * Pins the canonical encodings of PRD 12.10 to vectors computed independently with {@code sha256sum}. If one of
 * these changes, every published proof and the seal chain break: change the PRD and the browser verifier first.
 */
class ProofHashingTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void leafPadAndParentMatchThePublishedEncoding() {
        Node leaf = ProofHashing.leaf(ACCOUNT, new BigDecimal("1250.5"), "abc");
        assertThat(leaf.hash()).isEqualTo("d3addf88ff8620b5b80c2a55d7024dd876b4098d89ad6782d74eab9f46ea79c8");
        assertThat(leaf.sumString()).isEqualTo("1250.5000");

        Node pad = ProofHashing.pad(1);
        assertThat(pad.hash()).isEqualTo("20fb905c85cf74db456b608e3629a1587f5f56d45f934ca815ce4ab01d5ff8c5");
        assertThat(pad.sumString()).isEqualTo("0.0000");

        Node root = ProofHashing.parent(leaf, pad);
        assertThat(root.hash()).isEqualTo("8ac26c9a58c4da0d5c8ae84918d9a87a778dfbaae31256825c890634e10294a5");
        assertThat(root.sumString()).isEqualTo("1250.5000");
    }

    @Test
    void amountsAreFourDecimalPlainStringsAndNeverRounded() {
        assertThat(ProofHashing.amount(new BigDecimal("0"))).isEqualTo("0.0000");
        assertThat(ProofHashing.amount(new BigDecimal("1E+3"))).isEqualTo("1000.0000");
        assertThatThrownBy(() -> ProofHashing.amount(new BigDecimal("0.00001"))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void paddedSizeIsTheNextPowerOfTwoWithAtLeastOneLeaf() {
        assertThat(ProofHashing.paddedSize(0)).isEqualTo(1);
        assertThat(ProofHashing.paddedSize(1)).isEqualTo(1);
        assertThat(ProofHashing.paddedSize(2)).isEqualTo(2);
        assertThat(ProofHashing.paddedSize(3)).isEqualTo(4);
        assertThat(ProofHashing.paddedSize(1025)).isEqualTo(2048);
    }

    @Test
    void sealHashMatchesTheCanonicalEntryEncoding() {
        EntryData entry = new EntryData(UUID.fromString("22222222-2222-2222-2222-222222222222"), "DEPOSIT", null, null,
                Instant.parse("2026-10-19T08:00:00.123456Z"), "Deposit");
        // Given out of order: canonical() sorts lines by id.
        List<LineData> lines = List.of(
                new LineData(UUID.fromString("44444444-4444-4444-4444-444444444444"), ACCOUNT, "CREDIT",
                        new BigDecimal("100"), new BigDecimal("100"), null),
                new LineData(UUID.fromString("33333333-3333-3333-3333-333333333333"),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), "DEBIT",
                        new BigDecimal("100.0000"), new BigDecimal("100.0000"), null));
        String hash = ProofHashing.sealHash(ProofHashing.GENESIS_HASH, ProofHashing.canonical(entry, lines));
        assertThat(hash).isEqualTo("1f42c6e5cea0c84e24a3e26a58cdf3b78e5ce26968f5e17a3a2efdb9471d3687");
    }

    @Test
    void anyChangeToAnEntryChangesItsSeal() {
        EntryData entry = new EntryData(UUID.randomUUID(), "TRANSFER", null, null, Instant.parse("2026-10-19T08:00:00Z"),
                "Rent");
        LineData debit = new LineData(UUID.randomUUID(), UUID.randomUUID(), "DEBIT", new BigDecimal("10.0000"),
                new BigDecimal("90.0000"), "Bob");
        LineData credit = new LineData(UUID.randomUUID(), UUID.randomUUID(), "CREDIT", new BigDecimal("10.0000"),
                new BigDecimal("10.0000"), "Alice");
        String base = ProofHashing.canonical(entry, List.of(debit, credit));
        LineData edited = new LineData(credit.id(), credit.ledgerAccountId(), "CREDIT", new BigDecimal("10.0001"),
                credit.balanceAfter(), credit.counterparty());
        assertThat(ProofHashing.canonical(entry, List.of(debit, edited))).isNotEqualTo(base);
        EntryData renamed = new EntryData(entry.id(), entry.type(), null, null, entry.createdAt(), "Rent|x");
        assertThat(ProofHashing.canonical(renamed, List.of(debit, credit))).isNotEqualTo(base);
        assertThat(ProofHashing.sealHash("a".repeat(64), base)).isNotEqualTo(ProofHashing.sealHash("b".repeat(64), base));
    }
}
