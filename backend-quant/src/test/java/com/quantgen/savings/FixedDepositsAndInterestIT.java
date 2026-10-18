package com.quantgen.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/**
 * V2-M3 part 2: fixed deposits (F-SAV-3) and savings interest (F-SAV-4). Every interest figure below is computed by
 * hand as simple interest, principal × rate × days / 36500, rounded half-even to the paisa (milestone exit test).
 */
class FixedDepositsAndInterestIT extends IntegrationTest {

    @Autowired
    FixedDepositService deposits;

    @Autowired
    SavingsInterestService savingsInterest;

    @Autowired
    LedgerService ledger;

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.BUSINESS_ZONE);
    }

    @Test
    void bookingValidatesMovesThePrincipalAndLocksIt() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "60000.00");

        JsonNode rates = get("/api/v1/fixed-deposits/rates", alice.accessToken()).jsonBody();
        assertThat(rates.size()).isEqualTo(7);
        assertThat(rates.get(0).get("minDays").asInt()).isEqualTo(7);
        assertThat(rates.get(4).get("annualRate").asString()).isEqualTo("7.00");

        assertThat(book(alice, savings, "999.99", 365, "PAYOUT").status()).isEqualTo(400);
        assertThat(book(alice, savings, "1000000.01", 365, "PAYOUT").status()).isEqualTo(400);
        assertThat(book(alice, savings, "5000.00", 6, "PAYOUT").status()).isEqualTo(400);
        assertThat(book(alice, savings, "5000.00", 1826, "PAYOUT").status()).isEqualTo(400);
        assertThat(book(alice, savings, "5000.00", 365, "LATER").status()).isEqualTo(400);
        assertThat(book(bob, savings, "5000.00", 365, "PAYOUT").status()).isEqualTo(404);
        assertThat(book(alice, savings, "70000.00", 365, "PAYOUT").jsonBody().get("code").asString())
                .isEqualTo("INSUFFICIENT_FUNDS");

        UUID key = UUID.randomUUID();
        Response booked = postJson("/api/v1/fixed-deposits", bookBody(savings, "50000.00", 365, "PAYOUT"),
                Bank.authWithKey(alice, key));
        assertThat(booked.status()).isEqualTo(201);
        JsonNode fd = booked.jsonBody();
        assertThat(fd.get("principal").asString()).isEqualTo("50000.00");
        assertThat(fd.get("annualRate").asString()).isEqualTo("7.00");
        assertThat(fd.get("accruedInterest").asString()).isEqualTo("0.00");
        assertThat(fd.get("maturityAmount").asString()).isEqualTo("53500.00"); // 50000 × 7 × 365 / 36500 = 3500
        assertThat(fd.get("startDate").asString()).isEqualTo(today().toString());
        assertThat(fd.get("maturityDate").asString()).isEqualTo(today().plusDays(365).toString());
        assertThat(fd.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(fd.get("accountNumberMasked").asString()).startsWith("••••");
        // Same key, same body: replayed, booked once.
        Response replay = postJson("/api/v1/fixed-deposits", bookBody(savings, "50000.00", 365, "PAYOUT"),
                Bank.authWithKey(alice, key));
        assertThat(replay.jsonBody().get("id").asString()).isEqualTo(fd.get("id").asString());
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("10000.00");

        JsonNode accounts = get("/api/v1/accounts", alice.accessToken()).jsonBody();
        JsonNode fdAccount = null;
        for (JsonNode a : accounts) {
            if ("FIXED_DEPOSIT".equals(a.get("type").asString())) {
                fdAccount = a;
            }
        }
        assertThat(fdAccount).isNotNull();
        assertThat(fdAccount.get("balance").asString()).isEqualTo("50000.00");
        UUID fdAccountId = UUID.fromString(fdAccount.get("id").asString());

        // The FD's money can't be moved, topped up or opened through POST /accounts.
        assertThat(Bank.transfer(this, alice, fdAccountId, Bank.firstAccountNumber(this, alice), "10.00",
                UUID.randomUUID()).status()).isEqualTo(400);
        assertThat(Bank.transfer(this, alice, savings, fdAccount.get("accountNumber").asString(), "10.00",
                UUID.randomUUID()).status()).isEqualTo(400);
        assertThat(Bank.deposit(this, alice, fdAccountId, "10.00").status()).isEqualTo(400);
        assertThat(postJson("/api/v1/accounts", Map.of("type", "FIXED_DEPOSIT"), Bank.auth(alice)).status())
                .isEqualTo(400);
        assertThat(book(alice, fdAccountId, "1000.00", 30, "PAYOUT").status()).isEqualTo(400);

        assertThat(get("/api/v1/fixed-deposits", alice.accessToken()).jsonBody().size()).isEqualTo(1);
        assertThat(get("/api/v1/fixed-deposits", bob.accessToken()).jsonBody().size()).isZero();
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void maturityPaysOutExactInterestOnce() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "100000.00");
        UUID id = bookOk(alice, savings, "100000.00", 365, "PAYOUT");
        backdate(id, 365);
        assertThat(get("/api/v1/fixed-deposits", alice.accessToken()).jsonBody().get(0).get("accruedInterest")
                .asString()).isEqualTo("7000.00");

        assertThat(deposits.matureDue(today())).isGreaterThanOrEqualTo(1);
        assertThat(deposits.matureDue(today())).isZero();
        JsonNode fd = get("/api/v1/fixed-deposits", alice.accessToken()).jsonBody().get(0);
        assertThat(fd.get("status").asString()).isEqualTo("MATURED");
        assertThat(fd.get("accruedInterest").asString()).isEqualTo("7000.00"); // 100000 × 7 × 365 / 36500
        assertThat(fd.get("maturityAmount").asString()).isEqualTo("107000.00");
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("107000.00");
        UUID fdAccount = jdbc.queryForObject("SELECT account_id FROM fixed_deposits WHERE id = ?", UUID.class, id);
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, fdAccount))
                .isEqualTo("CLOSED");
        JsonNode interestTx = get("/api/v1/accounts/" + fdAccount + "/transactions?type=INTEREST", alice.accessToken())
                .jsonBody().get("content").get(0);
        assertThat(interestTx.get("amount").asString()).isEqualTo("7000.00");
        assertThat(get("/api/v1/notifications", alice.accessToken()).body()).contains("FD_MATURED");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void renewalRollsPrincipalPlusInterestIntoANewTerm() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "50000.00");
        UUID id = bookOk(alice, savings, "50000.00", 100, "RENEW");
        backdate(id, 100);
        LocalDate oldMaturity = today();

        deposits.matureDue(today());
        JsonNode list = get("/api/v1/fixed-deposits", alice.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(2);
        JsonNode renewed = list.get(0);
        JsonNode old = list.get(1);
        assertThat(old.get("status").asString()).isEqualTo("MATURED");
        assertThat(old.get("accruedInterest").asString()).isEqualTo("753.42"); // 50000 × 5.5 × 100 / 36500 = 753.4246
        assertThat(renewed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(renewed.get("principal").asString()).isEqualTo("50753.42");
        assertThat(renewed.get("startDate").asString()).isEqualTo(oldMaturity.toString());
        assertThat(renewed.get("maturityDate").asString()).isEqualTo(oldMaturity.plusDays(100).toString());
        assertThat(renewed.get("accountNumberMasked").asString()).isEqualTo(old.get("accountNumberMasked").asString());
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("0.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void prematureClosureNeedsStepUpPaysTheReducedRateAndRunsOnce() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "100000.00");
        Bank.deposit(this, alice, savings, "50000.00");
        UUID id = bookOk(alice, savings, "150000.00", 365, "PAYOUT");
        jdbc.update("UPDATE fixed_deposits SET start_date = ?, maturity_date = ? WHERE id = ?",
                Date.valueOf(today().minusDays(100)), Date.valueOf(today().plusDays(265)), id);

        String path = "/api/v1/fixed-deposits/" + id + "/close";
        Response noStepUp = postJson(path, Map.of(), Bank.authWithKey(alice, UUID.randomUUID()));
        assertThat(noStepUp.status()).isEqualTo(401);
        assertThat(noStepUp.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");
        String token = Bank.stepUpToken(this, alice, "FD_CLOSE");
        assertThat(postJson(path, Map.of(), withStepUp(bob, UUID.randomUUID(), Bank.stepUpToken(this, bob, "FD_CLOSE")))
                .status()).isEqualTo(404);
        assertThat(postJson(path, Map.of(), withStepUp(alice, UUID.randomUUID(), Bank.stepUpToken(this, alice,
                "TRANSFER"))).status()).isEqualTo(401); // a token for another action doesn't count

        // Two closes at once with different keys: exactly one pays out.
        UUID keyA = UUID.randomUUID();
        UUID keyB = UUID.randomUUID();
        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> postJson(path, Map.of(),
                withStepUp(alice, keyA, token)));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> postJson(path, Map.of(),
                withStepUp(alice, keyB, token)));
        List<Integer> statuses = List.of(a.get().status(), b.get().status()).stream().sorted().toList();
        assertThat(statuses).containsExactly(200, 409);
        UUID key = a.get().status() == 200 ? keyA : keyB; // the loser's key was rolled back with its request

        // 100 days held → slab 91–180 days (5.50%) − 1% = 4.50%: 150000 × 4.5 × 100 / 36500 = 1849.3151
        JsonNode closed = get("/api/v1/fixed-deposits", alice.accessToken()).jsonBody().get(0);
        assertThat(closed.get("status").asString()).isEqualTo("CLOSED");
        assertThat(closed.get("accruedInterest").asString()).isEqualTo("1849.32");
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("151849.32");
        Response replay = postJson(path, Map.of(), withStepUp(alice, key, token));
        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.jsonBody().get("status").asString()).isEqualTo("CLOSED");
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("151849.32");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void closingInTheFirstWeekPaysNoInterest() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "5000.00");
        UUID id = bookOk(alice, savings, "5000.00", 30, "PAYOUT");
        Response res = postJson("/api/v1/fixed-deposits/" + id + "/close", Map.of(),
                withStepUp(alice, UUID.randomUUID(), Bank.stepUpToken(this, alice, "FD_CLOSE")));
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("accruedInterest").asString()).isEqualTo("0.00");
        assertThat(res.jsonBody().get("maturityAmount").asString()).isEqualTo("5000.00");
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("5000.00");
    }

    @Test
    void savingsInterestAccruesDailyAndIsCreditedOnceAMonth() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "100000.00");
        UUID checking = UUID.fromString(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice))
                .jsonBody().get("id").asString());
        Bank.deposit(this, alice, checking, "100000.00");

        for (int d = 0; d < 30; d++) {
            savingsInterest.accrue(today().plusDays(d));
        }
        savingsInterest.accrue(today()); // re-running a day changes nothing
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_accruals WHERE account_id = ?", Integer.class,
                savings)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT amount FROM interest_accruals WHERE account_id = ? LIMIT 1",
                BigDecimal.class, savings)).isEqualByComparingTo("8.2192"); // 100000 × 3 / 36500 = 8.21918
        assertThat(jdbc.queryForObject("SELECT count(*) FROM interest_accruals WHERE account_id = ?", Integer.class,
                checking)).isZero(); // CHECKING earns nothing

        savingsInterest.credit(today().plusDays(29));
        // 30 × 8.2192 = 246.576 → 246.58 (and 100000 × 3 × 30 / 36500 = 246.5753 → 246.58)
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("100246.58");
        savingsInterest.credit(today().plusDays(29));
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("100246.58");
        JsonNode tx = get("/api/v1/accounts/" + savings + "/transactions?type=INTEREST", alice.accessToken())
                .jsonBody().get("content").get(0);
        assertThat(tx.get("description").asString()).isEqualTo("Savings interest");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private void backdate(UUID id, int days) {
        jdbc.update("UPDATE fixed_deposits SET start_date = ?, maturity_date = ? WHERE id = ?",
                Date.valueOf(today().minusDays(days)), Date.valueOf(today()), id);
    }

    private Map<String, String> withStepUp(TestUsers.Session s, UUID key, String token) {
        Map<String, String> h = new HashMap<>(Bank.authWithKey(s, key));
        h.put("X-Step-Up-Token", token);
        return h;
    }

    private static Map<String, Object> bookBody(UUID from, String amount, int days, String onMaturity) {
        return Map.of("fromAccountId", from.toString(), "amount", amount, "tenureDays", days, "onMaturity", onMaturity);
    }

    private Response book(TestUsers.Session s, UUID from, String amount, int days, String onMaturity) {
        return postJson("/api/v1/fixed-deposits", bookBody(from, amount, days, onMaturity),
                Bank.authWithKey(s, UUID.randomUUID()));
    }

    private UUID bookOk(TestUsers.Session s, UUID from, String amount, int days, String onMaturity) {
        Response res = book(s, from, amount, days, onMaturity);
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return UUID.fromString(res.jsonBody().get("id").asString());
    }
}
