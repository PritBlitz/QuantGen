package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.payment.gateway.RazorpaySignatures;
import com.quantgen.payment.service.TopupService;
import com.quantgen.support.Bank;
import com.quantgen.support.FakeRazorpayApi;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/**
 * V2-M2 part 5: Razorpay test-mode top-ups (PRD 7.9). Exit test of the milestone: a captured payment credits exactly
 * once whether verify or the webhook arrives first, or both at once.
 */
class TopupsIT extends IntegrationTest {

    static final String KEY_SECRET = "test-key-secret";
    static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Autowired
    FakeRazorpayApi razorpay;

    @Autowired
    TopupService topupService;

    @Autowired
    LedgerService ledger;

    @BeforeEach
    void switchOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'razorpay'");
        razorpay.down.set(false);
    }

    @AfterEach
    void switchOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'razorpay'");
        razorpay.down.set(false);
    }

    @Test
    void creatingATopupOnlyCreatesAnOrder() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);

        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'razorpay'");
        Response off = create(alice, acc, "500.00", UUID.randomUUID());
        assertThat(off.status()).isEqualTo(503);
        assertThat(off.jsonBody().get("code").asString()).isEqualTo("FEATURE_DISABLED");
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'razorpay'");

        assertThat(create(alice, acc, "100000.01", UUID.randomUUID()).status()).isEqualTo(400);
        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        assertThat(create(eve, acc, "500.00", UUID.randomUUID()).status()).isEqualTo(404);

        // Razorpay unreachable: 503, and the same key works once it is back.
        UUID key = UUID.randomUUID();
        razorpay.down.set(true);
        Response down = create(alice, acc, "500.00", key);
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.jsonBody().get("code").asString()).isEqualTo("GATEWAY_UNAVAILABLE");
        razorpay.down.set(false);

        Response ok = create(alice, acc, "500.00", key);
        assertThat(ok.status()).isEqualTo(201);
        JsonNode t = ok.jsonBody();
        assertThat(t.get("status").asString()).isEqualTo("CREATED");
        assertThat(t.get("currency").asString()).isEqualTo("INR");
        assertThat(t.get("gateway").asString()).isEqualTo("RAZORPAY");
        assertThat(t.get("gatewayKeyId").asString()).isEqualTo("rzp_test_fake000000");
        assertThat(t.get("transactionId").isNull()).isTrue();
        assertThat(razorpay.orders.get(t.get("gatewayOrderId").asString())).isEqualTo(50000L);
        assertThat(t.toString()).doesNotContain(KEY_SECRET);

        // A double-click returns the same order instead of opening a second checkout.
        Response replay = create(alice, acc, "500.00", key);
        assertThat(replay.jsonBody().get("id").asString()).isEqualTo(t.get("id").asString());
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("0.00");

        assertThat(get("/api/v1/accounts/" + acc + "/topups", alice.accessToken()).jsonBody().get("totalElements")
                .asInt()).isEqualTo(1);
        assertThat(get("/api/v1/accounts/" + acc + "/topups", eve.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/topups/" + t.get("id").asString(), eve.accessToken()).status()).isEqualTo(404);

        // Frozen accounts can't start a top-up.
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", acc);
        assertThat(create(alice, acc, "500.00", UUID.randomUUID()).status()).isEqualTo(422);
    }

    @Test
    void verifyChecksTheSignatureAndTheCapturedAmount() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        JsonNode t = create(alice, acc, "750.00", UUID.randomUUID()).jsonBody();
        String id = t.get("id").asString();
        String order = t.get("gatewayOrderId").asString();

        String authorizedOnly = razorpay.pay(order, "authorized");
        Response notCaptured = verify(alice, id, authorizedOnly, sign(order, authorizedOnly));
        assertThat(notCaptured.status()).isEqualTo(400);
        assertThat(notCaptured.jsonBody().get("code").asString()).isEqualTo("PAYMENT_VERIFICATION_FAILED");

        String paymentId = razorpay.pay(order, "captured");
        String good = sign(order, paymentId);
        // Change the first hex digit (a fixed "00" prefix would equal the real signature 1 time in 256).
        Response forged = verify(alice, id, paymentId, (good.charAt(0) == '0' ? "1" : "0") + good.substring(1));
        assertThat(forged.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOPUP_VERIFICATION_FAILED' "
                + "AND target_id = ?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE details LIKE ?", Integer.class,
                "%" + sign(order, paymentId) + "%")).isZero();

        TestUsers.Session eve = TestUsers.registerAndLogin(this);
        assertThat(verify(eve, id, paymentId, sign(order, paymentId)).status()).isEqualTo(404);

        Response ok = verify(alice, id, paymentId, sign(order, paymentId));
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.jsonBody().get("status").asString()).isEqualTo("CREDITED");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("750.00");

        JsonNode tx = get("/api/v1/accounts/" + acc + "/transactions", alice.accessToken()).jsonBody()
                .get("content").get(0);
        assertThat(tx.get("type").asString()).isEqualTo("TOPUP");
        assertThat(tx.get("counterparty").asString()).isEqualTo("Razorpay");
        assertThat(tx.get("id").asString()).isEqualTo(ok.jsonBody().get("transactionId").asString());

        // Verifying again is a no-op that returns the same body.
        Response again = verify(alice, id, paymentId, sign(order, paymentId));
        assertThat(again.status()).isEqualTo(200);
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("750.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOPUP_CREDITED' AND target_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(get("/api/v1/analytics/summary", alice.accessToken()).jsonBody().get("income").asString())
                .isEqualTo("750.00");
    }

    @Test
    void webhookThenVerifyCreditsOnce() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        JsonNode t = create(alice, acc, "300.00", UUID.randomUUID()).jsonBody();
        String order = t.get("gatewayOrderId").asString();
        String paymentId = razorpay.pay(order, "captured");

        assertThat(webhook(captured(order, paymentId, 30000), "evt_" + UUID.randomUUID()).status()).isEqualTo(200);
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("300.00");
        Response verify = verify(alice, t.get("id").asString(), paymentId, sign(order, paymentId));
        assertThat(verify.status()).isEqualTo(200);
        assertThat(verify.jsonBody().get("status").asString()).isEqualTo("CREDITED");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("300.00");
    }

    @Test
    void verifyThenWebhookCreditsOnceAndRepeatsAreIgnored() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        JsonNode t = create(alice, acc, "300.00", UUID.randomUUID()).jsonBody();
        String order = t.get("gatewayOrderId").asString();
        String paymentId = razorpay.pay(order, "captured");

        assertThat(verify(alice, t.get("id").asString(), paymentId, sign(order, paymentId)).status()).isEqualTo(200);
        String eventId = "evt_" + UUID.randomUUID();
        assertThat(webhook(captured(order, paymentId, 30000), eventId).status()).isEqualTo(200);
        assertThat(webhook(captured(order, paymentId, 30000), eventId).status()).isEqualTo(200);
        assertThat(webhook(captured(order, paymentId, 30000), "evt_" + UUID.randomUUID()).status()).isEqualTo(200);
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("300.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void verifyAndWebhookAtTheSameTimeCreditOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestUsers.Session alice = TestUsers.registerAndLogin(this);
            UUID acc = Bank.firstAccountId(this, alice);
            JsonNode t = create(alice, acc, "100.00", UUID.randomUUID()).jsonBody();
            String order = t.get("gatewayOrderId").asString();
            String paymentId = razorpay.pay(order, "captured");

            CompletableFuture<Integer> a = CompletableFuture.supplyAsync(
                    () -> verify(alice, t.get("id").asString(), paymentId, sign(order, paymentId)).status());
            CompletableFuture<Integer> b = CompletableFuture.supplyAsync(
                    () -> webhook(captured(order, paymentId, 10000), "evt_" + UUID.randomUUID()).status());
            assertThat(List.of(a.get(), b.get())).containsOnly(200);
            assertThat(Bank.balance(this, alice, acc)).isEqualTo("100.00");
        }
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void webhookRulesFailedExpiredAndFrozen() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        JsonNode t = create(alice, acc, "200.00", UUID.randomUUID()).jsonBody();
        String order = t.get("gatewayOrderId").asString();

        // Bad signature → 400; an unknown order or event type → 200 and ignored.
        String body = captured(order, "pay_x", 20000);
        Response bad = send(request("/api/v1/webhooks/razorpay").header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", "deadbeef").header("X-Razorpay-Event-Id", "evt_bad")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
        assertThat(bad.status()).isEqualTo(400);
        assertThat(webhook(captured("order_unknown", "pay_y", 20000), "evt_" + UUID.randomUUID()).status())
                .isEqualTo(200);
        assertThat(webhook("{\"event\":\"refund.created\",\"payload\":{}}", "evt_" + UUID.randomUUID()).status())
                .isEqualTo(200);

        // A captured amount that doesn't match the order is not credited.
        assertThat(webhook(captured(order, "pay_wrong", 1), "evt_" + UUID.randomUUID()).status()).isEqualTo(200);
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("0.00");

        // payment.failed marks the top-up FAILED…
        String failed = "{\"event\":\"payment.failed\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"pay_f\","
                + "\"order_id\":\"" + order + "\",\"amount\":20000,\"currency\":\"INR\",\"status\":\"failed\","
                + "\"error_description\":\"Card declined\"}}}}";
        webhook(failed, "evt_" + UUID.randomUUID());
        JsonNode afterFail = get("/api/v1/topups/" + t.get("id").asString(), alice.accessToken()).jsonBody();
        assertThat(afterFail.get("status").asString()).isEqualTo("FAILED");
        assertThat(afterFail.get("failureReason").asString()).isEqualTo("Card declined");

        // …but a later successful capture is still credited, even after expiry and on a frozen account.
        jdbc.update("UPDATE topups SET status = 'CREATED', created_at = now() - interval '31 minutes' WHERE id = ?::uuid",
                t.get("id").asString());
        topupService.expire();
        assertThat(get("/api/v1/topups/" + t.get("id").asString(), alice.accessToken()).jsonBody().get("status")
                .asString()).isEqualTo("EXPIRED");
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", acc);
        assertThat(webhook(captured(order, "pay_late", 20000), "evt_" + UUID.randomUUID()).status()).isEqualTo(200);
        assertThat(get("/api/v1/topups/" + t.get("id").asString(), alice.accessToken()).jsonBody().get("status")
                .asString()).isEqualTo("CREDITED");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("200.00");
    }

    // ------------------------------------------------------------------ helpers

    private Response create(TestUsers.Session s, UUID acc, String amount, UUID key) {
        return postJson("/api/v1/accounts/" + acc + "/topups", Map.of("amount", amount), Bank.authWithKey(s, key));
    }

    private Response verify(TestUsers.Session s, String topupId, String paymentId, String signature) {
        return postJson("/api/v1/topups/" + topupId + "/verify",
                Map.of("gatewayPaymentId", paymentId, "gatewaySignature", signature), Bank.auth(s));
    }

    private static String sign(String orderId, String paymentId) {
        return RazorpaySignatures.hmacHex(orderId + "|" + paymentId, KEY_SECRET);
    }

    private static String captured(String orderId, String paymentId, long paise) {
        return "{\"event\":\"payment.captured\",\"payload\":{\"payment\":{\"entity\":{\"id\":\"" + paymentId
                + "\",\"order_id\":\"" + orderId + "\",\"amount\":" + paise
                + ",\"currency\":\"INR\",\"status\":\"captured\"}}}}";
    }

    private Response webhook(String body, String eventId) {
        return send(request("/api/v1/webhooks/razorpay").header("Content-Type", "application/json")
                .header("X-Razorpay-Signature", RazorpaySignatures.hmacHex(body, WEBHOOK_SECRET))
                .header("X-Razorpay-Event-Id", eventId)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build());
    }
}
