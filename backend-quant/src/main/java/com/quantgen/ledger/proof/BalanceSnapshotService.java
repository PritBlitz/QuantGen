package com.quantgen.ledger.proof;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.service.AccountService;
import com.quantgen.common.crypto.FieldCrypto;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.ledger.proof.LedgerSealer.ChainHead;
import com.quantgen.ledger.proof.ProofHashing.Node;
import com.quantgen.ledger.service.SystemAccount;

/**
 * F-POB-2/3/5: the daily Merkle sum tree over every customer ledger account, inclusion proofs, and the solvency
 * view. A snapshot is tied to a seal-chain position: each balance is the sum of the lines of the entries sealed up
 * to that position, so anyone holding the ledger can recompute it. Amounts in the DTOs are 4-decimal strings, the
 * exact text that was hashed (PRD 12.10).
 */
@Service
public class BalanceSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(BalanceSnapshotService.class);

    static final String LOCK = "balance-snapshot";
    static final String SALT_CONTEXT = "qg-proof-salt-v1";
    static final int ROOTS_SHOWN = 30;

    public record ChainHeadDto(long position, String hash) {
    }

    /** {@code anchor}: the public git commit holding this root (F-POB-6), null until one is recorded. */
    public record RootDto(LocalDate date, String root, String totalLiabilities, int leafCount, ChainHeadDto chainHead,
                          AnchorDto anchor) {
    }

    public record AnchorDto(String repo, String commit, java.time.Instant committedAt) {
    }

    public record LeafDto(UUID accountId, String balance, String salt) {
    }

    public record PathNodeDto(String hash, String sum, String side) {
    }

    public record ProofDto(LocalDate date, LeafDto leaf, List<PathNodeDto> path, String root, String totalLiabilities) {
    }

    public record SolvencyDto(String totalLiabilities, String totalAssets, String ratio) {
    }

    private record Snapshot(UUID id, RootDto root) {
    }

    private final JdbcTemplate jdbc;
    private final AdvisoryLocks locks;
    private final LedgerSealer sealer;
    private final FieldCrypto crypto;
    private final AccountService accounts;
    /** Public URL of the anchor repository (F-POB-6); blank → roots carry no anchor. */
    private final String anchorRepo;

    BalanceSnapshotService(JdbcTemplate jdbc, AdvisoryLocks locks, LedgerSealer sealer, FieldCrypto crypto,
                           AccountService accounts,
                           @org.springframework.beans.factory.annotation.Value("${qg.proofs.anchor.repo-url:}") String anchorRepo) {
        this.anchorRepo = anchorRepo;
        this.jdbc = jdbc;
        this.locks = locks;
        this.sealer = sealer;
        this.crypto = crypto;
        this.accounts = accounts;
    }

    // ------------------------------------------------------------------ taking a snapshot

    @Transactional(readOnly = true)
    public boolean exists(LocalDate date) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM balance_snapshots WHERE snapshot_date = ?",
                Integer.class, Date.valueOf(date));
        return n != null && n > 0;
    }

    /**
     * Builds and stores the snapshot for {@code date} as of the current chain head; returns the existing one if
     * that date already has a snapshot (snapshots are immutable). Seal pending entries first to include them.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public RootDto take(LocalDate date) {
        locks.lockForTransaction(LOCK);
        Optional<Snapshot> existing = find(date);
        if (existing.isPresent()) {
            return existing.get().root();
        }
        ChainHead head = sealer.head();
        // Every customer ledger account, with the balance implied by the entries sealed up to the head.
        List<UUID> ids = new ArrayList<>();
        List<BigDecimal> balances = new ArrayList<>();
        jdbc.query("""
                SELECT la.id,
                       COALESCE(SUM(CASE WHEN l.direction = la.normal_side THEN l.amount ELSE -l.amount END), 0) AS balance
                  FROM ledger_accounts la
                  LEFT JOIN (ledger_lines l JOIN ledger_seals s ON s.entry_id = l.journal_entry_id AND s.position <= ?)
                         ON l.ledger_account_id = la.id
                 WHERE la.kind = 'CUSTOMER'
                 GROUP BY la.id
                """, rs -> {
            ids.add(rs.getObject("id", UUID.class));
            balances.add(rs.getBigDecimal("balance").setScale(4, RoundingMode.UNNECESSARY));
        }, head.position());

        // Leaves in ascending order of the account id's canonical (lowercase) string.
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing(i -> ids.get(i).toString()));

        int size = ProofHashing.paddedSize(ids.size());
        List<Node> level = new ArrayList<>(size);
        List<Object[]> rows = new ArrayList<>(2 * size);
        UUID snapshotId = UUID.randomUUID();
        for (int idx = 0; idx < size; idx++) {
            Node node;
            if (idx < ids.size()) {
                UUID accountId = ids.get(order.get(idx));
                BigDecimal balance = balances.get(order.get(idx));
                if (balance.signum() < 0) {
                    // Can't happen while postings keep customer balances >= 0; never publish a negative leaf.
                    throw new IllegalStateException("Negative sealed balance for a customer account; snapshot aborted");
                }
                String salt = salt(accountId, date);
                node = ProofHashing.leaf(accountId, balance, salt);
                rows.add(new Object[] {snapshotId, 0, idx, node.hash(), node.sum(), accountId, salt});
            } else {
                node = ProofHashing.pad(idx);
                rows.add(new Object[] {snapshotId, 0, idx, node.hash(), node.sum(), null, null});
            }
            level.add(node);
        }
        int depth = 0;
        while (level.size() > 1) {
            depth++;
            List<Node> next = new ArrayList<>(level.size() / 2);
            for (int i = 0; i < level.size(); i += 2) {
                Node parent = ProofHashing.parent(level.get(i), level.get(i + 1));
                rows.add(new Object[] {snapshotId, depth, i / 2, parent.hash(), parent.sum(), null, null});
                next.add(parent);
            }
            level = next;
        }
        Node root = level.getFirst();

        jdbc.update("INSERT INTO balance_snapshots (id, snapshot_date, root, total_liabilities, leaf_count, "
                        + "chain_position, chain_hash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshotId, Date.valueOf(date), root.hash(), root.sum(), ids.size(), head.position(), head.hash());
        jdbc.batchUpdate("INSERT INTO balance_snapshot_nodes (snapshot_id, level, idx, hash, sum, account_id, salt) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)", rows);
        log.info("Balance snapshot {} taken: {} account(s), chain position {}", date, ids.size(), head.position());
        return new RootDto(date, root.hash(), root.sumString(), ids.size(),
                new ChainHeadDto(head.position(), head.hash()), null);
    }

    /** Per account per day; derived from the index key under its own context, so it never equals a blind index. */
    private String salt(UUID accountId, LocalDate date) {
        return crypto.blindIndex(accountId + "|" + date, SALT_CONTEXT).substring(0, 32);
    }

    // ------------------------------------------------------------------ reading

    @Transactional(readOnly = true)
    public List<RootDto> roots() {
        return jdbc.query("""
                SELECT s.*, a.commit_sha, a.committed_at FROM balance_snapshots s
                  LEFT JOIN proof_anchors a ON a.snapshot_date = s.snapshot_date
                 ORDER BY s.snapshot_date DESC LIMIT ?
                """, (rs, i) -> {
            RootDto r = rootOf(rs);
            String sha = rs.getString("commit_sha");
            return sha == null || anchorRepo.isBlank() ? r : new RootDto(r.date(), r.root(), r.totalLiabilities(),
                    r.leafCount(), r.chainHead(), new AnchorDto(anchorRepo, sha, rs.getTimestamp("committed_at").toInstant()));
        }, ROOTS_SHOWN);
    }

    /** The caller's own account only: someone else's account, an unknown date or a missing leaf are all 404. */
    @Transactional(readOnly = true)
    public ProofDto proof(UUID userId, UUID accountId, LocalDate date) {
        accounts.requireOwned(userId, accountId);
        Snapshot snapshot = (date == null ? latest() : find(date))
                .orElseThrow(() -> ApiException.notFound("Snapshot"));
        List<Object[]> leaf = jdbc.query("SELECT idx, sum, salt FROM balance_snapshot_nodes "
                        + "WHERE snapshot_id = ? AND level = 0 AND account_id = ?",
                (rs, i) -> new Object[] {rs.getInt("idx"), rs.getBigDecimal("sum"), rs.getString("salt")},
                snapshot.id(), accountId);
        if (leaf.isEmpty()) {
            throw ApiException.notFound("Account in this snapshot");
        }
        int idx = (Integer) leaf.getFirst()[0];
        BigDecimal balance = (BigDecimal) leaf.getFirst()[1];
        String salt = (String) leaf.getFirst()[2];

        int depth = Integer.numberOfTrailingZeros(ProofHashing.paddedSize(snapshot.root().leafCount()));
        Map<Integer, Node> siblings = new HashMap<>();
        List<Integer> siblingIdx = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        args.add(snapshot.id());
        for (int level = 0, i = idx; level < depth; level++, i >>= 1) {
            siblingIdx.add(i ^ 1);
            where.append(level == 0 ? "" : " OR ").append("(level = ? AND idx = ?)");
            args.add(level);
            args.add(i ^ 1);
        }
        if (depth > 0) {
            jdbc.query("SELECT level, hash, sum FROM balance_snapshot_nodes WHERE snapshot_id = ? AND (" + where + ")",
                    rs -> {
                        siblings.put(rs.getInt("level"), new Node(rs.getString("hash"), rs.getBigDecimal("sum")));
                    }, args.toArray());
        }
        List<PathNodeDto> path = new ArrayList<>(depth);
        for (int level = 0; level < depth; level++) {
            Node sibling = siblings.get(level);
            if (sibling == null) {
                throw new IllegalStateException("Snapshot " + snapshot.root().date() + " is missing a node");
            }
            // An odd sibling index means the sibling is the right child.
            path.add(new PathNodeDto(sibling.hash(), sibling.sumString(),
                    (siblingIdx.get(level) & 1) == 1 ? "RIGHT" : "LEFT"));
        }
        RootDto root = snapshot.root();
        return new ProofDto(root.date(), new LeafDto(accountId, ProofHashing.amount(balance), salt), path,
                root.root(), root.totalLiabilities());
    }

    /** Live totals: customer balances against the bank's (simulated) cash, loan book and gateway receivables. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SolvencyDto solvency() {
        BigDecimal liabilities = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM ledger_accounts WHERE kind = 'CUSTOMER'", BigDecimal.class);
        BigDecimal assets = jdbc.queryForObject("SELECT COALESCE(SUM(balance), 0) FROM ledger_accounts WHERE id IN (?, ?, ?)",
                BigDecimal.class, SystemAccount.BANK_CASH.id(), SystemAccount.LOAN_RECEIVABLE.id(),
                SystemAccount.GATEWAY_CLEARING.id());
        BigDecimal ratio = liabilities.signum() == 0 ? BigDecimal.ZERO
                : assets.divide(liabilities, 4, RoundingMode.HALF_EVEN);
        return new SolvencyDto(ProofHashing.amount(liabilities), ProofHashing.amount(assets),
                ratio.setScale(4, RoundingMode.UNNECESSARY).toPlainString());
    }

    private Optional<Snapshot> find(LocalDate date) {
        return jdbc.query("SELECT * FROM balance_snapshots WHERE snapshot_date = ?",
                (rs, i) -> new Snapshot(rs.getObject("id", UUID.class), rootOf(rs)), Date.valueOf(date)).stream().findFirst();
    }

    private Optional<Snapshot> latest() {
        return jdbc.query("SELECT * FROM balance_snapshots ORDER BY snapshot_date DESC LIMIT 1",
                (rs, i) -> new Snapshot(rs.getObject("id", UUID.class), rootOf(rs))).stream().findFirst();
    }

    private static RootDto rootOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RootDto(rs.getObject("snapshot_date", LocalDate.class), rs.getString("root"),
                ProofHashing.amount(rs.getBigDecimal("total_liabilities")), rs.getInt("leaf_count"),
                new ChainHeadDto(rs.getLong("chain_position"), rs.getString("chain_hash")), null);
    }
}
