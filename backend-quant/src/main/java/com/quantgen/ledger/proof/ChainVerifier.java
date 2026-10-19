package com.quantgen.ledger.proof;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.ledger.proof.ProofHashing.EntryData;
import com.quantgen.ledger.proof.ProofHashing.LineData;

/**
 * F-POB-4: recomputes the seal chain from genesis against the journal entries and lines as they are now. A
 * changed entry or line, a line added to a sealed entry, an edited or missing seal all show up as the first
 * position whose recomputed hash, prev_hash or position doesn't match.
 */
@Component
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);
    private static final int PAGE = 1000;

    public record ChainReport(boolean intact, long checkedUpTo, Long firstBrokenPosition) {
    }

    private record SealRow(long position, String prevHash, String hash, EntryData entry) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;

    ChainVerifier(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public ChainReport verify() {
        ChainReport report = readTx.execute(status -> verifyInSnapshot());
        if (report != null && !report.intact()) {
            log.error("Ledger seal chain BROKEN at position {} (head {})", report.firstBrokenPosition(),
                    report.checkedUpTo());
        }
        return report;
    }

    private ChainReport verifyInSnapshot() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) FROM ledger_seals", Long.class);
        long head = max == null ? 0 : max;
        long expected = 1;
        String prev = ProofHashing.GENESIS_HASH;
        while (expected <= head) {
            List<SealRow> page = jdbc.query("""
                    SELECT s.position, s.prev_hash, s.hash,
                           e.id, e.type, e.reverses_entry_id, e.created_by, e.created_at, e.description
                      FROM ledger_seals s
                      JOIN journal_entries e ON e.id = s.entry_id
                     WHERE s.position >= ? AND s.position <= ?
                     ORDER BY s.position
                     LIMIT ?
                    """, (rs, i) -> new SealRow(rs.getLong("position"), rs.getString("prev_hash"),
                    rs.getString("hash"), LedgerSealer.entry(rs, i)), expected, head, PAGE);
            if (page.isEmpty()) {
                return new ChainReport(false, head, expected); // the rest of the chain is missing
            }
            Map<UUID, List<LineData>> lines = LedgerSealer.linesOf(jdbc,
                    page.stream().map(r -> r.entry().id()).toList());
            for (SealRow row : page) {
                if (row.position() != expected || !row.prevHash().equals(prev)) {
                    return new ChainReport(false, head, expected);
                }
                String recomputed = ProofHashing.sealHash(prev,
                        ProofHashing.canonical(row.entry(), lines.getOrDefault(row.entry().id(), List.of())));
                if (!recomputed.equals(row.hash())) {
                    return new ChainReport(false, head, expected);
                }
                prev = row.hash();
                expected++;
            }
        }
        return new ChainReport(true, head, null);
    }
}
