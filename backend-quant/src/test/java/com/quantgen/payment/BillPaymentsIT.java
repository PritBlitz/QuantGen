package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M2 part 3: simulated billers and bill payments (F-PAY-11). */
class BillPaymentsIT extends IntegrationTest {

    /** "Metro Power Distribution": a 10-digit account id. */
    static final String POWER = "b1000000-0000-4000-8000-000000000001";

    @Autowired
    LedgerService ledger;

    @Test
    void billersAreListedAndFiltered() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        JsonNode all = get("/api/v1/billers", alice.accessToken()).jsonBody();
        assertThat(all.size()).isEqualTo(12);
        assertThat(all.get(0).has("consumerNumberPattern")).isTrue();
        JsonNode gas = get("/api/v1/billers?category=GAS", alice.accessToken()).jsonBody();
        assertThat(gas.size()).isEqualTo(2);
        assertThat(get("/api/v1/billers?category=PIZZA", alice.accessToken()).status()).isEqualTo(400);
    }

    @Test
    void fetchIsValidatedAndStable() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        assertThat(fetch(alice, POWER, "12345").status()).isEqualTo(400);
        assertThat(fetch(alice, UUID.randomUUID().toString(), "1234567890").status()).isEqualTo(404);

        JsonNode first = fetch(alice, POWER, "1234567890").jsonBody();
        BigDecimal amount = new BigDecimal(first.get("amount").asString());
        assertThat(amount).isBetween(new BigDecimal("200.00"), new BigDecimal("4999.99"));
        assertThat(first.get("customerNameMasked").asString()).isEqualTo("Test U.");
        assertThat(first.get("dueDate").asString()).endsWith("-20");

        JsonNode again = fetch(alice, POWER, "1234567890").jsonBody();
        assertThat(again.get("billId").asString()).isEqualTo(first.get("billId").asString());
        assertThat(again.get("amount").asString()).isEqualTo(first.get("amount").asString());
    }

    @Test
    void billIsPaidOnceThroughTheLedger() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "10000.00");
        JsonNode bill = fetch(alice, POWER, "9876543210").jsonBody();
        String billId = bill.get("billId").asString();
        BigDecimal amount = new BigDecimal(bill.get("amount").asString());

        // Someone else can't pay (or see) it.
        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        assertThat(pay(eve, billId, Bank.firstAccountId(this, eve), UUID.randomUUID()).status()).isEqualTo(404);

        UUID key = UUID.randomUUID();
        Response paid = pay(alice, billId, acc, key);
        assertThat(paid.status()).isEqualTo(201);
        JsonNode receipt = paid.jsonBody();
        assertThat(receipt.get("billerName").asString()).isEqualTo("Metro Power Distribution");
        assertThat(receipt.get("category").asString()).isEqualTo("ELECTRICITY");
        assertThat(receipt.get("consumerNumberMasked").asString()).isEqualTo("••••3210");
        assertThat(new BigDecimal(Bank.balance(this, alice, acc)))
                .isEqualByComparingTo(new BigDecimal("10000.00").subtract(amount));

        JsonNode tx = get("/api/v1/accounts/" + acc + "/transactions", alice.accessToken()).jsonBody()
                .get("content").get(0);
        assertThat(tx.get("id").asString()).isEqualTo(receipt.get("transactionId").asString());
        assertThat(tx.get("type").asString()).isEqualTo("BILL_PAYMENT");
        assertThat(tx.get("counterparty").asString()).isEqualTo("Metro Power Distribution");

        Response replay = pay(alice, billId, acc, key);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.jsonBody().get("id").asString()).isEqualTo(receipt.get("id").asString());
        assertThat(pay(alice, billId, acc, UUID.randomUUID()).status()).isEqualTo(409);
        assertThat(fetch(alice, POWER, "9876543210").status()).isEqualTo(409);
        assertThat(new BigDecimal(Bank.balance(this, alice, acc)))
                .isEqualByComparingTo(new BigDecimal("10000.00").subtract(amount));

        JsonNode list = get("/api/v1/bill-payments", alice.accessToken()).jsonBody();
        assertThat(list.get("totalElements").asInt()).isEqualTo(1);
        assertThat(list.toString()).doesNotContain("9876543210");
        assertThat(get("/api/v1/bill-payments", eve.accessToken()).jsonBody().get("totalElements").asInt()).isZero();

        // Personal analytics counts it as spending, and it is the top (coarse) category.
        JsonNode summary = get("/api/v1/analytics/summary", alice.accessToken()).jsonBody();
        assertThat(new BigDecimal(summary.get("spending").asString())).isEqualByComparingTo(amount);
        assertThat(summary.get("topCategory").get("key").asString()).isEqualTo("BILLS");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void insufficientFundsAndExpiryLeaveTheBillUnpaid() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        String billId = fetch(alice, POWER, "1111111111").jsonBody().get("billId").asString();

        Response broke = pay(alice, billId, acc, UUID.randomUUID());
        assertThat(broke.status()).isEqualTo(422);
        assertThat(broke.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");

        Bank.deposit(this, alice, acc, "5000.00");
        jdbc.update("UPDATE bills SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", billId);
        assertThat(pay(alice, billId, acc, UUID.randomUUID()).status()).isEqualTo(409);

        // Fetching again renews the same bill, which can then be paid.
        assertThat(fetch(alice, POWER, "1111111111").jsonBody().get("billId").asString()).isEqualTo(billId);
        assertThat(pay(alice, billId, acc, UUID.randomUUID()).status()).isEqualTo(201);
    }

    @Test
    void concurrentPaysWithDifferentKeysPayOnce() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "10000.00");
        String billId = fetch(alice, POWER, "2222222222").jsonBody().get("billId").asString();

        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> pay(alice, billId, acc, UUID.randomUUID()).status());
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> pay(alice, billId, acc, UUID.randomUUID()).status());
        assertThat(java.util.List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_payments WHERE bill_id = ?::uuid", Integer.class, billId))
                .isEqualTo(1);
    }

    @Test
    void savedBillersAreMaskedPrivateAndCapped() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session eve = TestUsers.registerAndLogin(this);

        assertThat(save(alice, UUID.randomUUID().toString(), "9876543210", null).status()).isEqualTo(400);
        assertThat(save(alice, POWER, "12", null).jsonBody().get("fieldErrors").get(0).get("field").asString())
                .isEqualTo("consumerNumber");
        assertThat(save(alice, POWER, "9876543210", "Home\u0007").status()).isEqualTo(400);

        Response ok = save(alice, POWER, " 9876543210 ", "Home");
        assertThat(ok.status()).isEqualTo(201);
        JsonNode saved = ok.jsonBody();
        assertThat(saved.get("billerName").asString()).isEqualTo("Metro Power Distribution");
        assertThat(saved.get("consumerNumberMasked").asString()).isEqualTo("••••3210");
        assertThat(saved.get("nickname").asString()).isEqualTo("Home");
        assertThat(saved.toString()).doesNotContain("9876543210");
        Response dup = save(alice, POWER, "9876543210", "Again");
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("consumerNumber");

        // Private: Eve sees none and can't fetch or delete Alice's.
        assertThat(get("/api/v1/saved-billers", eve.accessToken()).jsonBody().size()).isZero();
        String id = saved.get("id").asString();
        assertThat(postJson("/api/v1/saved-billers/" + id + "/fetch", "", Bank.auth(eve)).status()).isEqualTo(404);
        assertThat(delete("/api/v1/saved-billers/" + id, eve.accessToken()).status()).isEqualTo(404);

        // Fetching a saved biller gives the same bill as fetching by number, ready for POST /bill-payments.
        JsonNode bill = postJson("/api/v1/saved-billers/" + id + "/fetch", "", Bank.auth(alice)).jsonBody();
        assertThat(bill.get("billId").asString()).isEqualTo(fetch(alice, POWER, "9876543210").jsonBody().get("billId")
                .asString());

        for (int i = 1; i < 20; i++) {
            assertThat(save(alice, POWER, String.format("10000000%02d", i), null).status()).isEqualTo(201);
        }
        Response capped = save(alice, POWER, "1000000099", null);
        assertThat(capped.status()).isEqualTo(422);
        assertThat(capped.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
        JsonNode list = get("/api/v1/saved-billers", alice.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(20);
        assertThat(list.get(0).get("id").asString()).isEqualTo(id); // oldest first

        assertThat(delete("/api/v1/saved-billers/" + id, alice.accessToken()).status()).isEqualTo(204);
        assertThat(delete("/api/v1/saved-billers/" + id, alice.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/saved-billers", alice.accessToken()).jsonBody().size()).isEqualTo(19);
    }

    private Response save(TestUsers.Session s, String billerId, String consumer, String nickname) {
        Map<String, Object> body = new java.util.HashMap<>(Map.of("billerId", billerId, "consumerNumber", consumer));
        if (nickname != null) {
            body.put("nickname", nickname);
        }
        return postJson("/api/v1/saved-billers", body, Bank.auth(s));
    }

    private Response fetch(TestUsers.Session s, String billerId, String consumer) {
        return postJson("/api/v1/billers/" + billerId + "/fetch", Map.of("consumerNumber", consumer), Bank.auth(s));
    }

    private Response pay(TestUsers.Session s, String billId, UUID from, UUID key) {
        return postJson("/api/v1/bill-payments", Map.of("billId", billId, "fromAccountId", from.toString()),
                Bank.authWithKey(s, key));
    }
}
