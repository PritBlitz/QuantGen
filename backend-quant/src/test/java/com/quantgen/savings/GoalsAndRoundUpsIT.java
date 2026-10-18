package com.quantgen.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M3 part 1: goal accounts (F-SAV-1, D-037) and round-ups (F-SAV-2). */
class GoalsAndRoundUpsIT extends IntegrationTest {

    @Autowired
    RoundUpService roundUps;

    @Autowired
    LedgerService ledger;

    @Test
    void goalsAreValidatedAndSitOutsideTheAccountCap() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        assertThat(postJson("/api/v1/accounts", Map.of("type", "GOAL", "target", "5000.00"), Bank.auth(alice)).status())
                .isEqualTo(400);
        assertThat(createGoal(alice, "Trip", "99.99", null).status()).isEqualTo(400);
        assertThat(createGoal(alice, "Trip", "5000.00", LocalDate.now(TimeConfig.BUSINESS_ZONE).minusDays(1))
                .status()).isEqualTo(400);
        assertThat(postJson("/api/v1/accounts", Map.of("type", "FIXED_DEPOSIT"), Bank.auth(alice)).status())
                .isEqualTo(400);

        LocalDate date = LocalDate.now(TimeConfig.BUSINESS_ZONE).plusMonths(6);
        Map<String, Object> body = new HashMap<>(Map.of("type", "GOAL", "name", "  Goa trip ", "target", "25000.00",
                "targetDate", date.toString(), "emoji", "🏖️"));
        Response created = postJson("/api/v1/accounts", body, Bank.auth(alice));
        assertThat(created.status()).isEqualTo(201);
        JsonNode goal = created.jsonBody();
        assertThat(goal.get("type").asString()).isEqualTo("GOAL");
        assertThat(goal.get("goal").get("name").asString()).isEqualTo("Goa trip");
        assertThat(goal.get("goal").get("target").asString()).isEqualTo("25000.00");
        assertThat(goal.get("goal").get("targetDate").asString()).isEqualTo(date.toString());
        assertThat(goal.get("goal").get("emoji").asString()).isEqualTo("🏖️");

        JsonNode list = get("/api/v1/accounts", alice.accessToken()).jsonBody();
        assertThat(list.get(0).get("goal").isNull()).isTrue();
        assertThat(list.get(1).get("goal").get("name").asString()).isEqualTo("Goa trip");

        // The everyday cap still counts only SAVINGS/CHECKING: 1 + 2 checking = 3, the 4th is refused.
        assertThat(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice)).status()).isEqualTo(201);
        assertThat(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice)).status()).isEqualTo(201);
        assertThat(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice)).status()).isEqualTo(422);
        for (int i = 2; i <= 10; i++) {
            assertThat(createGoal(alice, "Goal " + i, "1000.00", null).status()).isEqualTo(201);
        }
        Response eleventh = createGoal(alice, "One too many", "1000.00", null);
        assertThat(eleventh.status()).isEqualTo(422);
        assertThat(eleventh.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void goalsOnlyMoveMoneyBetweenTheOwnersAccounts() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        UUID bobSavings = Bank.firstAccountId(this, bob);
        Bank.deposit(this, alice, savings, "1000.00");
        Bank.deposit(this, bob, bobSavings, "1000.00");
        JsonNode goal = createGoal(alice, "Bike", "50000.00", null).jsonBody();
        UUID goalId = UUID.fromString(goal.get("id").asString());
        String goalNumber = goal.get("accountNumber").asString();

        assertThat(Bank.transfer(this, alice, savings, goalNumber, "400.00", UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(Bank.transfer(this, alice, goalId, Bank.firstAccountNumber(this, alice), "100.00", UUID.randomUUID())
                .status()).isEqualTo(201);
        assertThat(Bank.balance(this, alice, goalId)).isEqualTo("300.00");

        // Out to someone else, or in from someone else: refused.
        Response out = Bank.transfer(this, alice, goalId, Bank.firstAccountNumber(this, bob), "10.00", UUID.randomUUID());
        assertThat(out.status()).isEqualTo(400);
        assertThat(out.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("fromAccountId");
        Response in = Bank.transfer(this, bob, bobSavings, goalNumber, "10.00", UUID.randomUUID());
        assertThat(in.status()).isEqualTo(400);
        assertThat(in.jsonBody().get("fieldErrors").get(0).get("message").asString())
                .isEqualTo("no account with this number"); // same as an unknown number: no existence leak
        Response payee = postJson("/api/v1/beneficiaries", Map.of("accountNumber", goalNumber, "nickname", "Alice goal"),
                Bank.auth(bob));
        assertThat(payee.status()).isEqualTo(400);
        assertThat(payee.jsonBody().get("fieldErrors").get(0).get("message").asString())
                .isEqualTo("no account with this number");
        assertThat(get("/api/v1/payees/resolve?accountNumber=" + goalNumber, bob.accessToken()).status()).isEqualTo(404);
        assertThat(put("/api/v1/users/me/handle", Map.of("handle", "g" + UUID.randomUUID().toString().substring(0, 8)
                + "@qg", "accountId", goalId.toString()), alice.accessToken()).status()).isEqualTo(400);
        String billId = postJson("/api/v1/billers/b1000000-0000-4000-8000-000000000001/fetch",
                Map.of("consumerNumber", "5555555555"), Bank.auth(alice)).jsonBody().get("billId").asString();
        assertThat(postJson("/api/v1/bill-payments", Map.of("billId", billId, "fromAccountId", goalId.toString()),
                Bank.authWithKey(alice, UUID.randomUUID())).status()).isEqualTo(400);
        assertThat(Bank.balance(this, alice, goalId)).isEqualTo("300.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void roundUpsAccrueOnSpendsAndSweepOncePerDay() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "10000.00");
        UUID goalId = UUID.fromString(createGoal(alice, "Rainy day", "10000.00", null).jsonBody().get("id").asString());
        String bobNumber = Bank.firstAccountNumber(this, bob);

        assertThat(get("/api/v1/accounts/" + savings + "/round-up", alice.accessToken()).jsonBody().get("enabled")
                .asBoolean()).isFalse();
        assertThat(setRoundUp(alice, savings, true, 7, goalId).status()).isEqualTo(400);
        assertThat(setRoundUp(alice, savings, true, 10, null).status()).isEqualTo(400);
        assertThat(setRoundUp(alice, goalId, true, 10, goalId).status()).isEqualTo(400);
        UUID bobGoal = UUID.fromString(createGoal(bob, "Bob's", "1000.00", null).jsonBody().get("id").asString());
        assertThat(setRoundUp(alice, savings, true, 10, bobGoal).status()).isEqualTo(400);
        assertThat(setRoundUp(bob, savings, true, 10, goalId).status()).isEqualTo(404);
        JsonNode on = setRoundUp(alice, savings, true, 10, goalId).jsonBody();
        assertThat(on.get("enabled").asBoolean()).isTrue();
        assertThat(on.get("goalAccountId").asString()).isEqualTo(goalId.toString());

        Bank.transfer(this, alice, savings, bobNumber, "123.40", UUID.randomUUID());   // +6.60
        Bank.transfer(this, alice, savings, bobNumber, "50.00", UUID.randomUUID());    // exact: nothing
        Bank.transfer(this, alice, savings, createChecking(alice), "33.00", UUID.randomUUID()); // own account: nothing
        String billId = postJson("/api/v1/billers/b1000000-0000-4000-8000-000000000001/fetch",
                Map.of("consumerNumber", "4444444444"), Bank.auth(alice)).jsonBody().get("billId").asString();
        JsonNode bill = postJson("/api/v1/bill-payments", Map.of("billId", billId, "fromAccountId", savings.toString()),
                Bank.authWithKey(alice, UUID.randomUUID())).jsonBody();
        BigDecimal billAmount = new BigDecimal(bill.get("amount").asString());
        BigDecimal billRoundUp = RoundUpService.roundUp(billAmount, 10);
        int expectedRows = billRoundUp.signum() == 0 ? 1 : 2;
        waitForAccruals(savings, expectedRows);

        BigDecimal expected = new BigDecimal("6.60").add(billRoundUp);
        BigDecimal before = new BigDecimal(Bank.balance(this, alice, savings));
        LocalDate today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        assertThat(roundUps.sweep(today)).isEqualTo(1);
        assertThat(new BigDecimal(Bank.balance(this, alice, goalId))).isEqualByComparingTo(expected);
        assertThat(new BigDecimal(Bank.balance(this, alice, savings))).isEqualByComparingTo(before.subtract(expected));
        assertThat(roundUps.sweep(today)).isZero(); // nothing left to sweep
        JsonNode tx = get("/api/v1/accounts/" + goalId + "/transactions", alice.accessToken()).jsonBody()
                .get("content").get(0);
        assertThat(tx.get("description").asString()).isEqualTo("Round-up savings");

        // Next spend, but the account is drained before the sweep: the round-up is skipped, nothing overdraws.
        Bank.transfer(this, alice, savings, bobNumber, "1.50", UUID.randomUUID());     // +8.50
        waitForAccruals(savings, expectedRows + 1);
        BigDecimal rest = new BigDecimal(Bank.balance(this, alice, savings));
        Bank.transfer(this, alice, savings, bobNumber, rest.toPlainString(), UUID.randomUUID());
        roundUps.sweep(today);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM round_up_accruals WHERE source_account_id = ? "
                + "AND status = 'SKIPPED'", Integer.class, savings)).isGreaterThanOrEqualTo(1);
        assertThat(Bank.balance(this, alice, savings)).isEqualTo("0.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    private String createChecking(TestUsers.Session s) {
        return postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s)).jsonBody().get("accountNumber")
                .asString();
    }

    private void waitForAccruals(UUID account, int atLeast) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM round_up_accruals WHERE source_account_id = ?",
                    Integer.class, account);
            if (n != null && n >= atLeast) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("round-up accruals did not appear; outbox=" + jdbc.queryForList(
                "SELECT type, payload::text p, processed_at, attempts, last_error FROM outbox_events ORDER BY created_at DESC LIMIT 6")
                + " offsets=" + jdbc.queryForList("SELECT * FROM consumer_offsets LIMIT 10")
                + " settings=" + jdbc.queryForList("SELECT * FROM round_up_settings"));
    }

    private Response createGoal(TestUsers.Session s, String name, String target, LocalDate date) {
        Map<String, Object> body = new HashMap<>(Map.of("type", "GOAL", "name", name, "target", target));
        if (date != null) {
            body.put("targetDate", date.toString());
        }
        return postJson("/api/v1/accounts", body, Bank.auth(s));
    }

    private Response setRoundUp(TestUsers.Session s, UUID account, boolean enabled, int roundTo, UUID goal) {
        Map<String, Object> body = new HashMap<>(Map.of("enabled", enabled, "roundTo", roundTo));
        if (goal != null) {
            body.put("goalAccountId", goal.toString());
        }
        return put("/api/v1/accounts/" + account + "/round-up", body, s.accessToken());
    }
}
