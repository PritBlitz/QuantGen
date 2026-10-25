package com.quantgen.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.proof.BalanceSnapshotService;
import com.quantgen.ledger.proof.ChainVerifier;
import com.quantgen.ledger.proof.ChainVerifier.ChainReport;
import com.quantgen.ledger.proof.LedgerSealer;
import com.quantgen.ledger.proof.ProofHashing;
import com.quantgen.ledger.proof.ProofHashing.Node;
import com.quantgen.ledger.proof.ProofJobs;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M8 Proof of Balance: the seal chain, the daily Merkle sum tree, inclusion proofs, tamper detection. */
class ProofOfBalanceIT extends IntegrationTest {

    /** Snapshot dates far in the future, so they never clash with the scheduled job's "today". */
    private static final AtomicInteger DAY = new AtomicInteger();

    @Autowired
    LedgerSealer sealer;

    @Autowired
    ChainVerifier verifier;

    @Autowired
    BalanceSnapshotService snapshots;

    @Autowired
    ProofJobs jobs;

    private static LocalDate nextDate() {
        return LocalDate.of(2090, 1, 1).plusDays(DAY.getAndIncrement());
    }

    // ------------------------------------------------------------------ sealing

    @Test
    void everyCommittedEntryIsSealedExactlyOnceInAGapFreeChain() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);

        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> work = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                work.add(pool.submit(() -> assertThat(Bank.deposit(this, s, acc, "10.00").status()).isEqualTo(201)));
            }
            for (int i = 0; i < 4; i++) {
                work.add(pool.submit(() -> sealer.sealPending(true)));
            }
            for (Future<?> f : work) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        sealer.sealPending(true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries e WHERE NOT EXISTS "
                + "(SELECT 1 FROM ledger_seals s WHERE s.entry_id = e.id)", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_seals", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT max(position) FROM ledger_seals", Long.class));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_entries", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(*) FROM ledger_seals", Long.class));
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void sealsAreInsertOnly() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Bank.deposit(this, s, Bank.firstAccountId(this, s), "5.00");
        sealer.sealPending(true);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("UPDATE ledger_seals SET hash = repeat('0', 64) WHERE position = 1"))
                .hasMessageContaining("insert-only");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_seals"))
                .hasMessageContaining("insert-only");
    }

    @Test
    void tamperingWithASealedLineOrSealIsDetectedAndAlertsAdmins() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "77.00");
        sealer.sealPending(true);
        TestUsers.Session admin = Bank.admin(this);
        assertThat(get("/api/v1/admin/ledger/verify-chain", admin.accessToken()).jsonBody().get("intact").asBoolean())
                .isTrue();

        UUID lineId = jdbc.queryForObject("SELECT id FROM ledger_lines WHERE ledger_account_id = ? LIMIT 1", UUID.class, acc);
        long position = jdbc.queryForObject("SELECT s.position FROM ledger_seals s JOIN ledger_lines l "
                + "ON l.journal_entry_id = s.entry_id WHERE l.id = ?", Long.class, lineId);
        String counterparty = jdbc.queryForObject("SELECT counterparty FROM ledger_lines WHERE id = ?", String.class, lineId);

        // Someone with DB access edits history behind the triggers' back.
        jdbc.execute("ALTER TABLE ledger_lines DISABLE TRIGGER ledger_lines_immutable");
        try {
            jdbc.update("UPDATE ledger_lines SET counterparty = 'Mallory' WHERE id = ?", lineId);
            JsonNode report = get("/api/v1/admin/ledger/verify-chain", admin.accessToken()).jsonBody();
            assertThat(report.get("intact").asBoolean()).isFalse();
            assertThat(report.get("firstBrokenPosition").asLong()).isEqualTo(position);
            assertThat(report.get("checkedUpTo").asLong()).isGreaterThanOrEqualTo(position);

            // The nightly job (no actor) also audits it and notifies every admin.
            jobs.verifyAndRecord(null, false);
            JsonNode notes = get("/api/v1/notifications", admin.accessToken()).jsonBody();
            assertThat(notes.toString()).contains("LEDGER_INTEGRITY");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'LEDGER_CHAIN_BROKEN' "
                    + "AND target_id = ?", Long.class, String.valueOf(position))).isGreaterThanOrEqualTo(2);
        } finally {
            jdbc.update("UPDATE ledger_lines SET counterparty = ? WHERE id = ?", counterparty, lineId);
            jdbc.execute("ALTER TABLE ledger_lines ENABLE TRIGGER ledger_lines_immutable");
        }
        assertThat(verifier.verify().intact()).isTrue();

        // Rewriting a seal itself breaks the link at that position.
        String hash = jdbc.queryForObject("SELECT hash FROM ledger_seals WHERE position = ?", String.class, position);
        jdbc.execute("ALTER TABLE ledger_seals DISABLE TRIGGER ledger_seals_immutable");
        try {
            jdbc.update("UPDATE ledger_seals SET hash = repeat('a', 64) WHERE position = ?", position);
            ChainReport report = verifier.verify();
            assertThat(report.intact()).isFalse();
            assertThat(report.firstBrokenPosition()).isEqualTo(position);
        } finally {
            jdbc.update("UPDATE ledger_seals SET hash = ? WHERE position = ?", hash, position);
            jdbc.execute("ALTER TABLE ledger_seals ENABLE TRIGGER ledger_seals_immutable");
        }
        assertThat(verifier.verify().intact()).isTrue();
    }

    @Test
    void aCheckpointedRunCoversOnlyWhatWasSealedSinceAndStillCatchesAnEditedCheckpoint() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "11.00");
        sealer.sealPending(true);
        TestUsers.Session admin = Bank.admin(this);
        JsonNode full = get("/api/v1/admin/ledger/verify-chain", admin.accessToken()).jsonBody();
        assertThat(full.get("intact").asBoolean()).isTrue();
        assertThat(full.get("fromPosition").asLong()).isEqualTo(1);
        long checkpoint = full.get("checkedUpTo").asLong();
        UUID oldLine = jdbc.queryForObject("SELECT id FROM ledger_lines WHERE ledger_account_id = ? LIMIT 1", UUID.class, acc);

        Bank.deposit(this, s, acc, "12.00");
        sealer.sealPending(true);
        JsonNode incremental = get("/api/v1/admin/ledger/verify-chain?from=CHECKPOINT", admin.accessToken()).jsonBody();
        assertThat(incremental.get("intact").asBoolean()).isTrue();
        assertThat(incremental.get("fromPosition").asLong()).isEqualTo(checkpoint + 1);
        assertThat(incremental.get("checkedUpTo").asLong()).isGreaterThan(checkpoint);
        assertThat(get("/api/v1/admin/ledger/verify-chain?from=YESTERDAY", admin.accessToken()).status()).isEqualTo(400);

        // An edit before the checkpoint is the full run's job (the Sunday nightly and the admin default).
        String counterparty = jdbc.queryForObject("SELECT counterparty FROM ledger_lines WHERE id = ?", String.class, oldLine);
        jdbc.execute("ALTER TABLE ledger_lines DISABLE TRIGGER ledger_lines_immutable");
        try {
            jdbc.update("UPDATE ledger_lines SET counterparty = 'Mallory' WHERE id = ?", oldLine);
            assertThat(verifier.verify(true).intact()).isTrue();
            assertThat(verifier.verify(false).intact()).isFalse();
        } finally {
            jdbc.update("UPDATE ledger_lines SET counterparty = ? WHERE id = ?", counterparty, oldLine);
            jdbc.execute("ALTER TABLE ledger_lines ENABLE TRIGGER ledger_lines_immutable");
        }

        // Rewriting the checkpointed seal itself is caught by a checkpointed run.
        long latest = verifier.verify(false).checkedUpTo();
        String hash = jdbc.queryForObject("SELECT hash FROM ledger_seals WHERE position = ?", String.class, latest);
        jdbc.execute("ALTER TABLE ledger_seals DISABLE TRIGGER ledger_seals_immutable");
        try {
            jdbc.update("UPDATE ledger_seals SET hash = repeat('b', 64) WHERE position = ?", latest);
            ChainReport report = verifier.verify(true);
            assertThat(report.intact()).isFalse();
            assertThat(report.firstBrokenPosition()).isEqualTo(latest);
        } finally {
            jdbc.update("UPDATE ledger_seals SET hash = ? WHERE position = ?", hash, latest);
            jdbc.execute("ALTER TABLE ledger_seals ENABLE TRIGGER ledger_seals_immutable");
        }
        assertThat(verifier.verify(true).intact()).isTrue();
    }

    // ------------------------------------------------------------------ snapshots and proofs

    @Test
    void everyAccountsProofRecomputesToThePublishedRootAndTotal() {
        List<TestUsers.Session> users = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        String[] amounts = {"1250.50", "1.01", "49999.99"};
        for (String amount : amounts) {
            TestUsers.Session s = TestUsers.registerAndLogin(this);
            UUID acc = Bank.firstAccountId(this, s);
            assertThat(Bank.deposit(this, s, acc, amount).status()).isEqualTo(201);
            users.add(s);
            accounts.add(acc);
        }
        sealer.sealPending(true);
        LocalDate date = nextDate();
        snapshots.take(date);

        // Published without signing in, with a cache header.
        Response rootsRes = get("/api/v1/public/proofs/roots");
        assertThat(rootsRes.status()).isEqualTo(200);
        assertThat(rootsRes.header("Cache-Control")).contains("max-age=60").contains("public");
        JsonNode published = null;
        for (JsonNode r : rootsRes.jsonBody()) {
            if (r.get("date").asString().equals(date.toString())) {
                published = r;
            }
        }
        assertThat(published).isNotNull();
        BigDecimal total = jdbc.queryForObject("SELECT sum(balance) FROM ledger_accounts WHERE kind = 'CUSTOMER'",
                BigDecimal.class);
        assertThat(published.get("totalLiabilities").asString()).isEqualTo(ProofHashing.amount(total));
        assertThat(published.get("totalLiabilities").asString()).matches("\\d+\\.\\d{4}");
        assertThat(published.get("leafCount").asInt()).isEqualTo(
                jdbc.queryForObject("SELECT count(*) FROM ledger_accounts WHERE kind = 'CUSTOMER'", Integer.class));
        assertThat(published.get("chainHead").get("position").asLong()).isPositive();

        for (int i = 0; i < users.size(); i++) {
            Response res = get("/api/v1/proofs/accounts/" + accounts.get(i) + "?date=" + date, users.get(i).accessToken());
            assertThat(res.status()).isEqualTo(200);
            JsonNode proof = res.jsonBody();
            assertThat(proof.get("leaf").get("balance").asString()).isEqualTo(new BigDecimal(amounts[i]).setScale(4).toPlainString());
            assertThat(proof.get("leaf").get("salt").asString()).hasSize(32);
            assertThat(proof.get("root").asString()).isEqualTo(published.get("root").asString());
            assertBrowserVerifies(proof, published);
        }
        // No date: the latest snapshot.
        assertThat(get("/api/v1/proofs/accounts/" + accounts.getFirst(), users.getFirst().accessToken()).status())
                .isEqualTo(200);
        // A snapshot is frozen: taking the same date again returns it unchanged.
        assertThat(snapshots.take(date).root()).isEqualTo(published.get("root").asString());
    }

    @Test
    void proofsAreOwnerOnlyAndMissingSnapshotsOrLeavesAre404() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aliceAcc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, aliceAcc, "10.00");
        sealer.sealPending(true);
        LocalDate date = nextDate();
        snapshots.take(date);

        String path = "/api/v1/proofs/accounts/" + aliceAcc + "?date=" + date;
        assertThat(get(path, alice.accessToken()).status()).isEqualTo(200);
        assertThat(get(path, bob.accessToken()).status()).isEqualTo(404);
        assertThat(get(path).status()).isEqualTo(401);
        assertThat(get("/api/v1/proofs/accounts/" + UUID.randomUUID() + "?date=" + date, alice.accessToken()).status())
                .isEqualTo(404);
        assertThat(get("/api/v1/proofs/accounts/" + aliceAcc + "?date=1999-01-01", alice.accessToken()).status())
                .isEqualTo(404);
        Response bad = get("/api/v1/proofs/accounts/" + aliceAcc + "?date=yesterday", alice.accessToken());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(bad.jsonBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");

        // An account opened after the snapshot isn't in it.
        TestUsers.Session late = TestUsers.registerAndLogin(this);
        Response missing = get("/api/v1/proofs/accounts/" + Bank.firstAccountId(this, late) + "?date=" + date,
                late.accessToken());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.jsonBody().get("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void adminEndpointsAreAdminOnlyAndSolvencyAddsUp() {
        TestUsers.Session customer = TestUsers.registerAndLogin(this);
        assertThat(get("/api/v1/admin/ledger/verify-chain", customer.accessToken()).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/ledger/solvency", customer.accessToken()).status()).isEqualTo(403);

        TestUsers.Session admin = Bank.admin(this);
        JsonNode solvency = get("/api/v1/admin/ledger/solvency", admin.accessToken()).jsonBody();
        BigDecimal liabilities = new BigDecimal(solvency.get("totalLiabilities").asString());
        BigDecimal assets = new BigDecimal(solvency.get("totalAssets").asString());
        assertThat(solvency.get("totalLiabilities").asString()).matches("\\d+\\.\\d{4}");
        assertThat(liabilities.signum()).isPositive();
        assertThat(new BigDecimal(solvency.get("ratio").asString()))
                .isEqualByComparingTo(assets.divide(liabilities, 4, java.math.RoundingMode.HALF_EVEN));
    }

    /** The same walk as frontend-quant/src/lib/merkle.ts verifyBalanceProof. */
    private static void assertBrowserVerifies(JsonNode proof, JsonNode published) {
        JsonNode leaf = proof.get("leaf");
        Node node = ProofHashing.leaf(UUID.fromString(leaf.get("accountId").asString()),
                new BigDecimal(leaf.get("balance").asString()), leaf.get("salt").asString());
        for (JsonNode sib : proof.get("path")) {
            BigDecimal sum = new BigDecimal(sib.get("sum").asString());
            assertThat(sum.signum()).isNotNegative();
            assertThat(sib.get("sum").asString()).matches("\\d+\\.\\d{4}");
            Node sibling = new Node(sib.get("hash").asString(), sum);
            node = sib.get("side").asString().equals("LEFT")
                    ? ProofHashing.parent(sibling, node) : ProofHashing.parent(node, sibling);
        }
        assertThat(node.hash()).isEqualTo(published.get("root").asString());
        assertThat(node.sumString()).isEqualTo(published.get("totalLiabilities").asString());
        assertThat(proof.get("totalLiabilities").asString()).isEqualTo(published.get("totalLiabilities").asString());
    }
}
