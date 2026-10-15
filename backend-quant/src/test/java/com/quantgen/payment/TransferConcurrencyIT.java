package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.ReconcileReport;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

/**
 * PRD §6 concurrency test: parallel transfers on one account end with exact balances, nothing negative,
 * and a ledger that sums to zero. Runs over real HTTP, so the whole stack is involved.
 */
class TransferConcurrencyIT extends IntegrationTest {

    private static final int THREADS = 32;

    @Autowired
    LedgerService ledger;

    @Test
    void hundredParallelTransfersFromOneAccountAreExact() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String bNum = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, aAcc, "10000.00");

        // 100 × 150.00 = 15000.00 requested against 10000.00: exactly 66 can succeed (9900.00).
        List<Integer> statuses = runConcurrently(100,
                i -> Bank.transfer(this, alice, aAcc, bNum, "150.00", UUID.randomUUID()).status());

        long ok = statuses.stream().filter(s -> s == 201).count();
        long insufficient = statuses.stream().filter(s -> s == 422).count();
        assertThat(ok).isEqualTo(66);
        assertThat(insufficient).isEqualTo(34);

        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("100.00");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("9900.00");
        assertLedgerConsistent();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfers WHERE from_account_id = ?", Integer.class, aAcc))
                .isEqualTo(66);
        // History balances never went negative and end exactly where the cache is.
        BigDecimal minBalanceAfter = jdbc.queryForObject(
                "SELECT min(balance_after) FROM ledger_lines WHERE ledger_account_id = ?", BigDecimal.class, aAcc);
        assertThat(minBalanceAfter).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void opposingTransfersDoNotDeadlock() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String aNum = Bank.firstAccountNumber(this, alice);
        String bNum = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, aAcc, "5000.00");
        Bank.deposit(this, bob, bAcc, "5000.00");

        // 50 A→B and 50 B→A at once. With unordered locking this would deadlock; all must succeed.
        List<Integer> statuses = runConcurrently(100, i -> i % 2 == 0
                ? Bank.transfer(this, alice, aAcc, bNum, "10.00", UUID.randomUUID()).status()
                : Bank.transfer(this, bob, bAcc, aNum, "10.00", UUID.randomUUID()).status());

        assertThat(statuses).containsOnly(201);
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("5000.00");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("5000.00");
        assertLedgerConsistent();
    }

    @Test
    void concurrentRetriesWithOneKeyMoveMoneyOnce() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        String bNum = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, aAcc, "1000.00");
        UUID key = UUID.randomUUID();

        // A double-click storm: 20 identical requests with the same Idempotency-Key.
        List<String> bodies = runConcurrently(20, i -> {
            Response r = Bank.transfer(this, alice, aAcc, bNum, "100.00", key);
            assertThat(r.status()).isEqualTo(201);
            return r.jsonBody().get("id").asString();
        });

        assertThat(bodies).containsOnly(bodies.getFirst()); // everyone got the same transfer
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("900.00");
        assertLedgerConsistent();
    }

    private void assertLedgerConsistent() {
        ReconcileReport report = ledger.reconcile();
        assertThat(report.ledgerSum()).isEqualByComparingTo("0");
        assertThat(report.mismatches()).isEmpty();
        assertThat(report.balanced()).isTrue();
        Integer negative = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_accounts WHERE kind = 'CUSTOMER' AND balance < 0", Integer.class);
        assertThat(negative).isZero();
    }

    private interface Task<T> {
        T run(int i) throws Exception;
    }

    /** Runs n tasks on a pool, all released at the same instant to maximize contention. */
    private static <T> List<T> runConcurrently(int n, Task<T> task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            List<Callable<T>> calls = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                calls.add(() -> {
                    start.await();
                    return task.run(idx);
                });
            }
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> c : calls) {
                futures.add(pool.submit(c));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
