package com.quantgen.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M9 virtual cards and the merchant sandbox (PRD 12.11). */
class CardsIT extends IntegrationTest {

    @Autowired
    CardService cards;

    @Autowired
    LedgerService ledger;

    @BeforeEach
    void cardsOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'cards'");
    }

    @AfterEach
    void cardsOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'cards'");
    }

    record Card(UUID id, UUID accountId, String pan, String expiry) {
    }

    // ------------------------------------------------------------------ issuing and controls

    @Test
    void oneCardPerEverydayAccountOwnerOnly() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);

        Response created = postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(s));
        assertThat(created.status()).isEqualTo(201);
        JsonNode card = created.jsonBody();
        assertThat(card.get("last4").asString()).matches("\\d{4}");
        assertThat(card.get("expiry").asString()).matches("(0[1-9]|1[0-2])/\\d{2}");
        assertThat(card.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(card.get("controls").get("online").asBoolean()).isTrue();
        assertThat(card.get("controls").get("perTxnLimit").asString()).isEqualTo("25000.00");
        assertThat(card.get("controls").get("blockedCategories").toString()).isEqualTo("[\"GAMBLING\"]");
        assertThat(card.toString()).doesNotContain("pan").doesNotContain("9100");

        assertThat(postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(s)).status()).isEqualTo(409);
        assertThat(postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(other)).status()).isEqualTo(404);
        assertThat(get("/api/v1/cards", other.accessToken()).jsonBody().size()).isZero();
        assertThat(get("/api/v1/cards", s.accessToken()).jsonBody().size()).isEqualTo(1);

        // A goal account can't have a card.
        Response goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Trip", "target", "1000.00"),
                Bank.auth(s));
        assertThat(goal.status()).isEqualTo(201);
        assertThat(postJson("/api/v1/accounts/" + goal.jsonBody().get("id").asString() + "/cards", "", Bank.auth(s))
                .status()).isEqualTo(404);
        // The PAN is stored encrypted, not in clear.
        String stored = jdbc.queryForObject("SELECT pan_enc FROM cards WHERE account_id = ?", String.class, acc);
        assertThat(stored).doesNotContain(card.get("last4").asString() + "\"").doesNotMatch("\\d{16}");
    }

    @Test
    void flagOffAnswers503() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        cardsOff();
        Response res = postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(s));
        assertThat(res.status()).isEqualTo(503);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("FEATURE_DISABLED");
        assertThat(authorize(s, "9100000000000000", "01/30", "123", "10.00", "OTHER", UUID.randomUUID()).status())
                .isEqualTo(503);
    }

    @Test
    void revealNeedsStepUpAndIsNeverCached() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID cardId = UUID.fromString(postJson("/api/v1/accounts/" + Bank.firstAccountId(this, s) + "/cards", "",
                Bank.auth(s)).jsonBody().get("id").asString());

        Response denied = postJson("/api/v1/cards/" + cardId + "/reveal", "", Bank.auth(s));
        assertThat(denied.status()).isEqualTo(401);
        assertThat(denied.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "CARD_REVEAL"));
        Response res = postJson("/api/v1/cards/" + cardId + "/reveal", "", h);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.header("Cache-Control")).contains("no-store");
        JsonNode body = res.jsonBody();
        String pan = body.get("pan").asString();
        assertThat(pan).matches("9100 \\d{4} \\d{4} \\d{4}");
        assertThat(CardService.luhnValid(pan.replace(" ", ""))).isTrue();
        assertThat(body.get("cvv").asString()).matches("\\d{3}");
        assertThat(body.get("hideAfterSeconds").asInt()).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'CARD_REVEALED' AND target_id = ?",
                Long.class, cardId.toString())).isEqualTo(1);

        // Another user's card is 404 even with a valid token of their own.
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        Map<String, String> oh = new HashMap<>(Bank.auth(other));
        oh.put("X-Step-Up-Token", Bank.stepUpToken(this, other, "CARD_REVEAL"));
        assertThat(postJson("/api/v1/cards/" + cardId + "/reveal", "", oh).status()).isEqualTo(404);
    }

    @Test
    void controlsAreValidatedAndFreezeShowsInStatus() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Response bad = patchJson("/api/v1/cards/" + card.id() + "/controls", Map.of("perTxnLimit", "200000.01"), Bank.auth(s));
        assertThat(bad.status()).isEqualTo(400);
        assertThat(patchJson("/api/v1/cards/" + card.id() + "/controls", Map.of("blockedCategories", List.of("CASINO")),
                Bank.auth(s)).status()).isEqualTo(400);

        JsonNode dto = patchJson("/api/v1/cards/" + card.id() + "/controls",
                Map.of("frozen", true, "perTxnLimit", "500.00", "blockedCategories", List.of("TRAVEL", "FUEL")),
                Bank.auth(s)).jsonBody();
        assertThat(dto.get("status").asString()).isEqualTo("FROZEN");
        assertThat(dto.get("controls").get("perTxnLimit").asString()).isEqualTo("500.00");
        assertThat(dto.get("controls").get("blockedCategories").toString()).isEqualTo("[\"TRAVEL\",\"FUEL\"]");
        assertThat(dto.get("controls").get("online").asBoolean()).isTrue(); // untouched

        TestUsers.Session other = TestUsers.registerAndLogin(this);
        assertThat(patchJson("/api/v1/cards/" + card.id() + "/controls", Map.of("frozen", false), Bank.auth(other))
                .status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ the sandbox

    @Test
    void authorizeCaptureRefundLeavesBalancesAndHoldsExact() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        assertThat(Bank.deposit(this, s, card.accountId(), "1000.00").status()).isEqualTo(201);
        BigDecimal clearingBefore = clearing();

        UUID key = UUID.randomUUID();
        Response auth = authorize(s, card, "300.00", "DINING", key);
        assertThat(auth.status()).isEqualTo(200);
        assertThat(auth.jsonBody().get("status").asString()).isEqualTo("APPROVED");
        String authId = auth.jsonBody().get("authId").asString();
        assertAccount(s, card.accountId(), "1000.00", "300.00", "700.00");

        // A retried checkout with the same key replays; it never holds twice.
        Response replay = authorize(s, card, "300.00", "DINING", key);
        assertThat(replay.jsonBody().get("authId").asString()).isEqualTo(authId);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertAccount(s, card.accountId(), "1000.00", "300.00", "700.00");

        // Partial capture: the hold is settled whole, the rest of it is freed.
        Response cap = settle(s, authId, "capture", "250.00", UUID.randomUUID());
        assertThat(cap.status()).isEqualTo(200);
        assertThat(cap.jsonBody().get("status").asString()).isEqualTo("CAPTURED");
        assertAccount(s, card.accountId(), "750.00", "0.00", "750.00");
        assertThat(settle(s, authId, "capture", null, UUID.randomUUID()).status()).isEqualTo(409);

        assertThat(settle(s, authId, "refund", "100.00", UUID.randomUUID()).jsonBody().get("refundedAmount").asString())
                .isEqualTo("100.00");
        assertAccount(s, card.accountId(), "850.00", "0.00", "850.00");
        Response tooMuch = settle(s, authId, "refund", "150.01", UUID.randomUUID());
        assertThat(tooMuch.status()).isEqualTo(400);
        Response rest = settle(s, authId, "refund", null, UUID.randomUUID());
        assertThat(rest.jsonBody().get("status").asString()).isEqualTo("REFUNDED");
        assertAccount(s, card.accountId(), "1000.00", "0.00", "1000.00");
        assertThat(settle(s, authId, "refund", null, UUID.randomUUID()).status()).isEqualTo(409);

        assertThat(clearing()).isEqualByComparingTo(clearingBefore);
        assertThat(ledger.reconcile().balanced()).isTrue();

        JsonNode txns = get("/api/v1/accounts/" + card.accountId() + "/transactions", s.accessToken()).jsonBody();
        assertThat(txns.toString()).contains("\"CARD\"").contains("\"REFUND\"");
    }

    @Test
    void cardSpendingIsCategorizedByMerchantAndRefundsNetOut() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "1000.00");
        String dining = authorize(s, card, "200.00", "DINING", UUID.randomUUID()).jsonBody().get("authId").asString();
        settle(s, dining, "capture", null, UUID.randomUUID());
        settle(s, dining, "refund", "50.00", UUID.randomUUID());
        String fuel = authorize(s, card, "80.00", "FUEL", UUID.randomUUID()).jsonBody().get("authId").asString();
        settle(s, fuel, "capture", null, UUID.randomUUID());

        Map<String, String> byCategory = new HashMap<>();
        for (JsonNode row : get("/api/v1/insights/spending", s.accessToken()).jsonBody()) {
            byCategory.put(row.get("key").asString(), row.get("amount").asString());
        }
        assertThat(byCategory).containsEntry("FOOD", "150.00").containsEntry("TRANSPORT", "80.00");
    }

    @Test
    void captureAndRefundAreForTheCheckoutUserOnlyAndNeedAKey() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "500.00");
        String authId = authorize(s, card, "100.00", "GROCERY", UUID.randomUUID()).jsonBody().get("authId").asString();

        TestUsers.Session other = TestUsers.registerAndLogin(this);
        assertThat(settle(other, authId, "capture", null, UUID.randomUUID()).status()).isEqualTo(404);
        Response noKey = postJson("/api/v1/sandbox/merchant/" + authId + "/capture", "{}", Bank.auth(s));
        assertThat(noKey.status()).isEqualTo(400);
        assertThat(postJson("/api/v1/sandbox/merchant/authorize", Map.of(), Bank.auth(s)).status()).isEqualTo(400);

        UUID key = UUID.randomUUID();
        assertThat(settle(s, authId, "capture", null, key).status()).isEqualTo(200);
        Response again = settle(s, authId, "capture", null, key);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
        assertAccount(s, card.accountId(), "400.00", "0.00", "400.00");
    }

    @Test
    void declinesCarryAReasonAndNotifyTheCardholder() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session shop = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "1000.00");

        assertDeclined(authorize(shop, card.pan(), card.expiry(), wrongCvv(card), "10.00", "OTHER", UUID.randomUUID()),
                "Card details don't match");
        assertDeclined(authorize(shop, card.pan(), "01/29", currentCvv(card), "10.00", "OTHER", UUID.randomUUID()),
                "Card details don't match");
        assertDeclined(authorize(shop, "9100000000000000", card.expiry(), "123", "10.00", "OTHER", UUID.randomUUID()),
                "Card details don't match");
        assertDeclined(authorize(shop, card, "10.00", "GAMBLING", UUID.randomUUID()), "Merchant category is blocked");
        assertDeclined(authorize(shop, card, "25000.01", "OTHER", UUID.randomUUID()), "Over the per-transaction limit");
        assertDeclined(authorize(shop, card, "1000.01", "OTHER", UUID.randomUUID()), "Insufficient funds");
        patchJson("/api/v1/cards/" + card.id() + "/controls", Map.of("online", false), Bank.auth(s));
        assertDeclined(authorize(shop, card, "10.00", "OTHER", UUID.randomUUID()), "Online payments are off");
        patchJson("/api/v1/cards/" + card.id() + "/controls", Map.of("online", true, "frozen", true), Bank.auth(s));
        assertDeclined(authorize(shop, card, "10.00", "OTHER", UUID.randomUUID()), "Card is frozen");

        assertAccount(s, card.accountId(), "1000.00", "0.00", "1000.00");
        long declined = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = 'CARD_DECLINED'",
                Long.class, s.userId());
        assertThat(declined).isEqualTo(7); // every decline on the real card; none for the made-up number
        String body = jdbc.queryForObject("SELECT body FROM notifications WHERE user_id = ? AND type = 'CARD_DECLINED' "
                + "ORDER BY created_at DESC LIMIT 1", String.class, s.userId());
        assertThat(body).contains("•••• " + card.pan().substring(12)).doesNotContain(card.pan());
    }

    @Test
    void theCvvOfThePrevious30SecondWindowIsAccepted() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "100.00");
        long window = cards.currentWindow();
        String previous = cards.cvv(card.pan(), window - 1);
        String older = cards.cvv(card.pan(), window - 2);
        Response ok = authorize(s, card.pan(), card.expiry(), previous, "10.00", "OTHER", UUID.randomUUID());
        assertThat(ok.jsonBody().get("status").asString()).isEqualTo("APPROVED");
        if (!older.equals(previous) && !older.equals(cards.cvv(card.pan(), cards.currentWindow()))) {
            assertDeclined(authorize(s, card.pan(), card.expiry(), older, "10.00", "OTHER", UUID.randomUUID()),
                    "Card details don't match");
        }
    }

    @Test
    void concurrentAuthorizationsNeverHoldMoreThanTheBalance() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "1000.00");
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Future<Response>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> authorize(s, card, "400.00", "OTHER", UUID.randomUUID())));
            }
            int approved = 0;
            for (Future<Response> f : results) {
                Response r = f.get(60, TimeUnit.SECONDS);
                assertThat(r.status()).isEqualTo(200);
                if ("APPROVED".equals(r.jsonBody().get("status").asString())) {
                    approved++;
                } else {
                    assertThat(r.jsonBody().get("reason").asString()).isEqualTo("Insufficient funds");
                }
            }
            assertThat(approved).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertAccount(s, card.accountId(), "1000.00", "800.00", "200.00");
    }

    // ------------------------------------------------------------------ disputes (F-CARD-4)

    /** A ₹400.00 payment on {@code s}'s card, run (authorized and captured) by another user as the merchant. */
    private record Paid(Card card, TestUsers.Session merchant, String authId) {
    }

    private Paid paid(TestUsers.Session s) {
        Card card = issue(s);
        Bank.deposit(this, s, card.accountId(), "1000.00");
        TestUsers.Session merchant = TestUsers.registerAndLogin(this);
        String authId = authorize(merchant, card, "400.00", "ELECTRONICS", UUID.randomUUID()).jsonBody().get("authId")
                .asString();
        assertThat(settle(merchant, authId, "capture", null, UUID.randomUUID()).status()).isEqualTo(200);
        return new Paid(card, merchant, authId);
    }

    private Response dispute(TestUsers.Session s, Paid p, Map<String, Object> body) {
        return postJson("/api/v1/cards/" + p.card().id() + "/payments/" + p.authId() + "/dispute", body, Bank.auth(s));
    }

    private Response resolve(TestUsers.Session admin, String disputeId, String outcome, UUID key) {
        return postJson("/api/v1/admin/card-disputes/" + disputeId + "/resolve",
                Map.of("outcome", outcome, "reason", "Checked with the merchant"), Bank.authWithKey(admin, key));
    }

    @Test
    void aRefundedDisputeReturnsTheMoneyOnceAndCapsTheMerchantsRefund() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Paid p = paid(s);
        TestUsers.Session admin = Bank.admin(this);
        BigDecimal clearingBefore = clearing();

        JsonNode payment = get("/api/v1/cards/" + p.card().id() + "/payments", s.accessToken()).jsonBody()
                .get("content").get(0);
        assertThat(payment.get("disputableAmount").asString()).isEqualTo("400.00");
        assertThat(payment.get("openDisputeId").isNull()).isTrue();

        // Only the cardholder: the merchant and strangers get 404.
        Map<String, Object> body = Map.of("reason", "NOT_RECEIVED", "amount", "150.00", "note", "Never arrived");
        assertThat(dispute(p.merchant(), p, body).status()).isEqualTo(404);
        assertThat(get("/api/v1/cards/" + p.card().id() + "/payments", p.merchant().accessToken()).status())
                .isEqualTo(404);
        assertThat(dispute(s, p, Map.of("reason", "NOT_RECEIVED", "amount", "400.01")).status()).isEqualTo(400);

        Response opened = dispute(s, p, body);
        assertThat(opened.status()).isEqualTo(201);
        String id = opened.jsonBody().get("id").asString();
        assertThat(opened.jsonBody().get("status").asString()).isEqualTo("OPEN");
        assertThat(opened.jsonBody().get("last4").asString()).isEqualTo(p.card().pan().substring(12));
        assertThat(dispute(s, p, body).status()).isEqualTo(409); // one open dispute per payment
        assertThat(get("/api/v1/cards/" + p.card().id() + "/payments", s.accessToken()).jsonBody().get("content").get(0)
                .get("disputableAmount").asString()).isEqualTo("250.00");

        // The merchant can refund only what isn't under dispute.
        assertThat(settle(p.merchant(), p.authId(), "refund", "250.01", UUID.randomUUID()).status()).isEqualTo(400);
        assertThat(settle(p.merchant(), p.authId(), "refund", null, UUID.randomUUID()).jsonBody().get("refundedAmount")
                .asString()).isEqualTo("250.00");
        assertThat(settle(p.merchant(), p.authId(), "refund", null, UUID.randomUUID()).status()).isEqualTo(409);

        // Customers can't resolve; an admin can, once.
        assertThat(resolve(s, id, "REFUNDED", UUID.randomUUID()).status()).isEqualTo(403);
        JsonNode queue = get("/api/v1/admin/card-disputes?status=OPEN&size=100", admin.accessToken()).jsonBody();
        assertThat(queue.toString()).contains(id).contains(s.userId().toString());
        UUID key = UUID.randomUUID();
        Response refunded = resolve(admin, id, "REFUNDED", key);
        assertThat(refunded.status()).isEqualTo(200);
        assertThat(refunded.jsonBody().get("status").asString()).isEqualTo("REFUNDED");
        assertThat(resolve(admin, id, "REFUNDED", key).header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(resolve(admin, id, "REJECTED", UUID.randomUUID()).status()).isEqualTo(409);

        assertAccount(s, p.card().accountId(), "1000.00", "0.00", "1000.00");
        assertThat(jdbc.queryForObject("SELECT status FROM card_authorizations WHERE id = ?::uuid", String.class,
                p.authId())).isEqualTo("REFUNDED");
        assertThat(clearing()).isEqualByComparingTo(clearingBefore.subtract(new BigDecimal("400.00")));
        assertThat(ledger.reconcile().balanced()).isTrue();
        assertThat(get("/api/v1/notifications", s.accessToken()).body()).contains("CARD_DISPUTE_RESOLVED");
        assertThat(get("/api/v1/card-disputes", s.accessToken()).jsonBody().get(0).get("resolutionReason").asString())
                .isEqualTo("Checked with the merchant");
        // The customer's note never reaches the audit log.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE details LIKE '%Never arrived%'",
                Long.class)).isZero();
    }

    @Test
    void rejectedAndLateDisputesMoveNoMoneyAndConcurrentResolvesRefundOnce() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        Paid late = paid(s);
        jdbc.update("UPDATE card_authorizations SET captured_at = now() - interval '61 days' WHERE id = ?::uuid",
                late.authId());
        assertThat(dispute(s, late, Map.of("reason", "OTHER")).status()).isEqualTo(409);

        TestUsers.Session t = TestUsers.registerAndLogin(this);
        Paid p = paid(t);
        String rejected = dispute(t, p, Map.of("reason", "DUPLICATE", "amount", "100.00")).jsonBody().get("id").asString();
        assertThat(resolve(admin, rejected, "REJECTED", UUID.randomUUID()).jsonBody().get("status").asString())
                .isEqualTo("REJECTED");
        assertAccount(t, p.card().accountId(), "600.00", "0.00", "600.00");

        // A second dispute after a rejection is allowed; two admins resolving it at once refund it once.
        Response second = dispute(t, p, Map.of("reason", "NOT_AS_DESCRIBED"));
        assertThat(second.status()).as(second.body()).isEqualTo(201);
        String id = second.jsonBody().get("id").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> resolve(admin, id, "REFUNDED", UUID.randomUUID()).status());
            Future<Integer> b = pool.submit(() -> resolve(admin, id, "REFUNDED", UUID.randomUUID()).status());
            assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertAccount(t, p.card().accountId(), "1000.00", "0.00", "1000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM card_refunds WHERE authorization_id = ?::uuid", Long.class,
                p.authId())).isEqualTo(1);
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private Card issue(TestUsers.Session s) {
        UUID acc = Bank.firstAccountId(this, s);
        JsonNode created = postJson("/api/v1/accounts/" + acc + "/cards", "", Bank.auth(s)).jsonBody();
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "CARD_REVEAL"));
        JsonNode revealed = postJson("/api/v1/cards/" + created.get("id").asString() + "/reveal", "", h).jsonBody();
        return new Card(UUID.fromString(created.get("id").asString()), acc, revealed.get("pan").asString().replace(" ", ""),
                revealed.get("expiry").asString());
    }

    private String currentCvv(Card card) {
        return cards.cvv(card.pan(), cards.currentWindow());
    }

    private String wrongCvv(Card card) {
        long w = cards.currentWindow();
        for (int i = 0; i < 1000; i++) {
            String c = String.format("%03d", i);
            if (!c.equals(cards.cvv(card.pan(), w)) && !c.equals(cards.cvv(card.pan(), w - 1))
                    && !c.equals(cards.cvv(card.pan(), w + 1))) {
                return c;
            }
        }
        throw new IllegalStateException();
    }

    private Response authorize(TestUsers.Session s, Card card, String amount, String category, UUID key) {
        return authorize(s, card.pan(), card.expiry(), currentCvv(card), amount, category, key);
    }

    private Response authorize(TestUsers.Session s, String pan, String expiry, String cvv, String amount,
                               String category, UUID key) {
        return postJson("/api/v1/sandbox/merchant/authorize", Map.of("cardNumber", pan, "expiry", expiry, "cvv", cvv,
                "amount", amount, "merchant", "QuantGen Store", "category", category), Bank.authWithKey(s, key));
    }

    private Response settle(TestUsers.Session s, String authId, String action, String amount, UUID key) {
        return postJson("/api/v1/sandbox/merchant/" + authId + "/" + action,
                amount == null ? Map.of() : Map.of("amount", amount), Bank.authWithKey(s, key));
    }

    private static void assertDeclined(Response res, String reason) {
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("status").asString()).isEqualTo("DECLINED");
        assertThat(res.jsonBody().get("reason").asString()).isEqualTo(reason);
    }

    private void assertAccount(TestUsers.Session s, UUID acc, String balance, String holds, String available) {
        JsonNode dto = get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody();
        assertThat(dto.get("balance").asString()).isEqualTo(balance);
        assertThat(dto.get("holdsTotal").asString()).isEqualTo(holds);
        assertThat(dto.get("availableBalance").asString()).isEqualTo(available);
    }

    private BigDecimal clearing() {
        return jdbc.queryForObject("SELECT balance FROM ledger_accounts WHERE id = ?", BigDecimal.class,
                SystemAccount.CARD_CLEARING.id());
    }
}
