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
 * F-POB-4: recomputes the seal chain against the journal entries and lines as they are now. A changed entry or
 * line, a line added to a sealed entry, an edited or missing seal all show up as the first position whose recomputed
 * hash, prev_hash or position doesn't match. A full run starts from genesis; a checkpointed run starts after the
 * latest checkpoint, whose seal must still carry the recorded hash, so it only covers what was sealed since (older
 * entries are covered by the next full run). Every intact run records its head as a checkpoint (V29).
 */
@Component
public class ChainVerifier {

    private static final Logger log = LoggerFactory.getLogger(ChainVerifier.class);
    private static final int PAGE = 1000;

    /** {@code fromPosition}: where recomputing started (1 = genesis). */
    public record ChainReport(boolean intact, long checkedUpTo, Long firstBrokenPosition, long fromPosition) {
    }

    private record SealRow(long position, String prevHash, String hash, EntryData entry) {
    }

    private record Checkpoint(long position, String hash, String sealHash) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate readTx;

    ChainVerifier(JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.readTx = new TransactionTemplate(txManager);
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** From genesis. */
    public ChainReport verify() {
        return verify(false);
    }

    public ChainReport verify(boolean fromCheckpoint) {
        ChainReport report = readTx.execute(status -> verifyInSnapshot(fromCheckpoint));
        if (report != null && !report.intact()) {
            log.error("Ledger seal chain BROKEN at position {} (head {})", report.firstBrokenPosition(),
                    report.checkedUpTo());
        } else if (report != null && report.checkedUpTo() > 0) {
            jdbc.update("""
                    INSERT INTO ledger_chain_checkpoints (position, hash)
                    SELECT position, hash FROM ledger_seals WHERE position = ?
                    ON CONFLICT (position) DO NOTHING
                    """, report.checkedUpTo());
        }
        return report;
    }

    private ChainReport verifyInSnapshot(boolean fromCheckpoint) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(position), 0) FROM ledger_seals", Long.class);
        long head = max == null ? 0 : max;
        long expected = 1;
        String prev = ProofHashing.GENESIS_HASH;
        if (fromCheckpoint) {
            List<Checkpoint> latest = jdbc.query("""
                    SELECT c.position, c.hash, s.hash AS seal_hash FROM ledger_chain_checkpoints c
                      LEFT JOIN ledger_seals s ON s.position = c.position
                     WHERE c.position <= ? ORDER BY c.position DESC LIMIT 1
                    """, (rs, i) -> new Checkpoint(rs.getLong("position"), rs.getString("hash"),
                    rs.getString("seal_hash")), head);
            if (!latest.isEmpty()) {
                Checkpoint cp = latest.getFirst();
                if (!cp.hash().equals(cp.sealHash())) {
                    // The checkpointed seal was edited or removed since it was verified.
                    return new ChainReport(false, head, cp.position(), cp.position());
                }
                expected = cp.position() + 1;
                prev = cp.hash();
            }
        }
        long from = expected;
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
                return new ChainReport(false, head, expected, from); // the rest of the chain is missing
            }
            Map<UUID, List<LineData>> lines = LedgerSealer.linesOf(jdbc,
                    page.stream().map(r -> r.entry().id()).toList());
            for (SealRow row : page) {
                if (row.position() != expected || !row.prevHash().equals(prev)) {
                    return new ChainReport(false, head, expected, from);
                }
                String recomputed = ProofHashing.sealHash(prev,
                        ProofHashing.canonical(row.entry(), lines.getOrDefault(row.entry().id(), List.of())));
                if (!recomputed.equals(row.hash())) {
                    return new ChainReport(false, head, expected, from);
                }
                prev = row.hash();
                expected++;
            }
        }
        return new ChainReport(true, head, null, from);
    }
}
