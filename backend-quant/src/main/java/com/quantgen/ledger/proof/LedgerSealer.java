package com.quantgen.ledger.proof;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.ledger.proof.ProofHashing.EntryData;
import com.quantgen.ledger.proof.ProofHashing.LineData;

/**
 * F-POB-1 / D-026: appends committed journal entries to the {@code ledger_seals} hash chain, after commit and off
 * the money path. One sealer at a time (advisory lock), so positions are gap-free on any number of instances.
 * <p>
 * Entries are found by creation time. The first pass after startup scans all history; later passes look back
 * {@link #LOOKBACK} from now, which covers any transaction that was still open when an earlier pass ran.
 */
@Component
public class LedgerSealer {

    private static final Logger log = LoggerFactory.getLogger(LedgerSealer.class);

    static final String LOCK = "ledger-sealer";
    static final int BATCH = 500;
    static final Duration LOOKBACK = Duration.ofHours(1);

    private final JdbcTemplate jdbc;
    private final AdvisoryLocks locks;
    private final Clock clock;
    private final TransactionTemplate tx;
    private volatile boolean fullScanDone;

    LedgerSealer(JdbcTemplate jdbc, AdvisoryLocks locks, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Scheduled(fixedDelayString = "${qg.proofs.seal-millis:2000}")
    void scheduled() {
        try {
            int sealed = sealPending(!fullScanDone);
            if (sealed > 0) {
                log.debug("Sealed {} journal entr(ies)", sealed);
            }
        } catch (RuntimeException e) {
            log.error("Ledger sealing failed", e);
        }
    }

    /** Seals every committed, unsealed entry (all history when {@code fullScan}). Returns how many were sealed. */
    public int sealPending(boolean fullScan) {
        Instant since = fullScan ? Instant.EPOCH : clock.instant().minus(LOOKBACK);
        int total = 0;
        int sealed;
        do {
            sealed = tx.execute(status -> sealBatch(since));
            total += sealed;
        } while (sealed == BATCH);
        if (fullScan) {
            fullScanDone = true;
        }
        return total;
    }

    /** The latest seal, or position 0 with the genesis hash when nothing is sealed yet. */
    public ChainHead head() {
        List<ChainHead> last = jdbc.query("SELECT position, hash FROM ledger_seals ORDER BY position DESC LIMIT 1",
                (rs, i) -> new ChainHead(rs.getLong("position"), rs.getString("hash")));
        return last.isEmpty() ? new ChainHead(0, ProofHashing.GENESIS_HASH) : last.getFirst();
    }

    public record ChainHead(long position, String hash) {
    }

    private int sealBatch(Instant since) {
        locks.lockForTransaction(LOCK);
        List<EntryData> batch = jdbc.query("""
                SELECT e.id, e.type, e.reverses_entry_id, e.created_by, e.created_at, e.description
                  FROM journal_entries e
                 WHERE e.created_at >= ?
                   AND NOT EXISTS (SELECT 1 FROM ledger_seals s WHERE s.entry_id = e.id)
                 ORDER BY e.created_at, e.id
                 LIMIT ?
                """, LedgerSealer::entry, Timestamp.from(since), BATCH);
        if (batch.isEmpty()) {
            return 0;
        }
        Map<UUID, List<LineData>> lines = linesOf(jdbc, batch.stream().map(EntryData::id).toList());
        ChainHead head = head();
        long position = head.position();
        String prev = head.hash();
        List<Object[]> rows = new ArrayList<>(batch.size());
        for (EntryData e : batch) {
            String hash = ProofHashing.sealHash(prev, ProofHashing.canonical(e, lines.getOrDefault(e.id(), List.of())));
            position++;
            rows.add(new Object[] {position, e.id(), prev, hash});
            prev = hash;
        }
        jdbc.batchUpdate("INSERT INTO ledger_seals (position, entry_id, prev_hash, hash) VALUES (?, ?, ?, ?)", rows);
        return batch.size();
    }

    static EntryData entry(ResultSet rs, int i) throws SQLException {
        return new EntryData(rs.getObject("id", UUID.class), rs.getString("type"),
                rs.getObject("reverses_entry_id", UUID.class), rs.getObject("created_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("description"));
    }

    /** The lines of the given entries, grouped by entry id. */
    static Map<UUID, List<LineData>> linesOf(JdbcTemplate jdbc, List<UUID> entryIds) {
        Map<UUID, List<LineData>> byEntry = new HashMap<>();
        if (entryIds.isEmpty()) {
            return byEntry;
        }
        String in = String.join(",", Collections.nCopies(entryIds.size(), "?"));
        jdbc.query("SELECT id, journal_entry_id, ledger_account_id, direction, amount, balance_after, counterparty "
                        + "FROM ledger_lines WHERE journal_entry_id IN (" + in + ")",
                rs -> {
                    byEntry.computeIfAbsent(rs.getObject("journal_entry_id", UUID.class), k -> new ArrayList<>())
                            .add(new LineData(rs.getObject("id", UUID.class),
                                    rs.getObject("ledger_account_id", UUID.class), rs.getString("direction"),
                                    rs.getBigDecimal("amount"), rs.getBigDecimal("balance_after"),
                                    rs.getString("counterparty")));
                }, entryIds.toArray());
        return byEntry;
    }
}
