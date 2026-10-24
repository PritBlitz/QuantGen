package com.quantgen.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M4 core side: intents are proposed, rendered by the server, and only run when the user approves. */
class IntentsIT extends IntegrationTest {

    @org.junit.jupiter.api.BeforeEach
    void developerAccessOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'mcp'");
    }

    @org.junit.jupiter.api.AfterEach
    void developerAccessOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'mcp'");
    }

    private String pat(TestUsers.Session s, String scope) {
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "TOKEN_CREATE"));
        return postJson("/api/v1/users/me/tokens", Map.of("name", "MCP", "scope", scope, "expiresInDays", 7), h)
                .jsonBody().get("token").asString();
    }

    private Response propose(String bearer, String kind, Map<String, Object> payload) {
        return postJson("/api/v1/intents", Map.of("kind", kind, "payload", payload,
                "rationale", "You asked me to do this.\nIt fits your budget."), Map.of("Authorization", "Bearer " + bearer));
    }

    private Response approve(TestUsers.Session s, String intentId, UUID key, boolean stepUp) {
        Map<String, String> h = new HashMap<>(Bank.authWithKey(s, key));
        if (stepUp) {
            h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "INTENT_APPROVE"));
        }
        return postJson("/api/v1/intents/" + intentId + "/approve", "", h);
    }

    @Test
    void aTokenProposesTheServerRendersAndOnlyTheStepUpApprovalMovesMoney() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "10000.00");
        String friendNumber = Bank.firstAccountNumber(this, friend);

        assertThat(propose(pat(s, "read"), "TRANSFER", Map.of()).status()).isEqualTo(403);

        String token = pat(s, "read+propose");
        Response res = propose(token, "TRANSFER", Map.of("fromAccountId", from.toString(),
                "toAccountNumber", friendNumber, "amount", "2500.00", "note", "Dinner"));
        assertThat(res.status()).isEqualTo(201);
        JsonNode intent = res.jsonBody();
        assertThat(intent.get("status").asString()).isEqualTo("PROPOSED");
        assertThat(intent.get("proposedBy").get("agent").asString()).isEqualTo("MCP_CLIENT");
        assertThat(intent.get("summary").get("title").asString()).isEqualTo("Send ₹2,500.00 to Test U.");
        assertThat(intent.get("summary").get("lines").toString()).contains("••••" + friendNumber.substring(8))
                .contains("Dinner").contains("You haven't paid this account before");
        assertThat(intent.get("riskLevel").asString()).isEqualTo("HIGH");
        assertThat(intent.get("requiresStepUp").asBoolean()).isTrue();
        assertThat(intent.get("rationale").asString()).isEqualTo("You asked me to do this. It fits your budget.");
        assertThat(intent.has("payload")).isFalse();
        assertThat(Bank.balance(this, s, from)).isEqualTo("10000.00");
        String id = intent.get("id").asString();

        // The token can't approve, and approval needs the human present.
        assertThat(postJson("/api/v1/intents/" + id + "/approve", "", Map.of("Authorization", "Bearer " + token,
                "Idempotency-Key", UUID.randomUUID().toString())).status()).isEqualTo(403);
        Response noStepUp = approve(s, id, UUID.randomUUID(), false);
        assertThat(noStepUp.status()).isEqualTo(401);
        assertThat(noStepUp.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        UUID key = UUID.randomUUID();
        Response ok = approve(s, id, key, true);
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.jsonBody().get("status").asString()).isEqualTo("EXECUTED");
        assertThat(ok.jsonBody().get("resultRef").get("type").asString()).isEqualTo("TRANSFER");
        assertThat(Bank.balance(this, s, from)).isEqualTo("7500.00");
        assertThat(Bank.balance(this, friend, Bank.firstAccountId(this, friend))).isEqualTo("2500.00");
        JsonNode transfer = get("/api/v1/transfers", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(transfer.get("source").asString()).isEqualTo("INTENT");
        assertThat(transfer.get("id").asString()).isEqualTo(ok.jsonBody().get("resultRef").get("id").asString());

        // Retrying with the same key replays; a fresh approval of a decided intent is refused.
        assertThat(approve(s, id, key, false).header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(approve(s, id, UUID.randomUUID(), true).status()).isEqualTo(409);
        assertThat(Bank.balance(this, s, from)).isEqualTo("7500.00");
    }

    @Test
    void aRefusalAtApprovalIsAFailedIntentNotAnError() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "1000.00");
        String id = propose(s.accessToken(), "TRANSFER", Map.of("fromAccountId", from.toString(),
                "toAccountNumber", Bank.firstAccountNumber(this, friend), "amount", "900.00")).jsonBody().get("id").asString();
        // The money is spent elsewhere before the user approves: re-validation catches it.
        Bank.transfer(this, s, from, Bank.firstAccountNumber(this, friend), "500.00", UUID.randomUUID());

        UUID key = UUID.randomUUID();
        Response res = approve(s, id, key, true);
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("status").asString()).isEqualTo("FAILED");
        assertThat(res.jsonBody().get("failureCode").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(approve(s, id, key, false).jsonBody().get("status").asString()).isEqualTo("FAILED"); // replay
        assertThat(Bank.balance(this, s, from)).isEqualTo("500.00");
    }

    @Test
    void expiredRejectedAndOtherPeoplesIntentsCantBeApproved() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Map<String, Object> payload = Map.of("fromAccountId", from.toString(),
                "toAccountNumber", Bank.firstAccountNumber(this, other), "amount", "10.00");

        String old = propose(s.accessToken(), "TRANSFER", payload).jsonBody().get("id").asString();
        jdbc.update("UPDATE intents SET expires_at = now() - interval '1 minute' WHERE id = ?", UUID.fromString(old));
        Response expired = approve(s, old, UUID.randomUUID(), true);
        assertThat(expired.status()).isEqualTo(409);
        assertThat(expired.jsonBody().get("code").asString()).isEqualTo("INTENT_EXPIRED");
        assertThat(get("/api/v1/intents?status=EXPIRED", s.accessToken()).body()).contains(old);

        String rejected = propose(s.accessToken(), "TRANSFER", payload).jsonBody().get("id").asString();
        assertThat(approve(other, rejected, UUID.randomUUID(), true).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/intents/" + rejected + "/reject", "", Bank.auth(other)).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/intents/" + rejected + "/reject", "", Bank.auth(s)).jsonBody().get("status")
                .asString()).isEqualTo("REJECTED");
        assertThat(approve(s, rejected, UUID.randomUUID(), true).status()).isEqualTo(409);
        assertThat(get("/api/v1/intents", other.accessToken()).jsonBody().size()).isZero();
        assertThat(get("/api/v1/intents?status=PROPOSED", s.accessToken()).jsonBody().size()).isZero();
    }

    @Test
    void proposalsAreValidatedBeforeAnythingIsStored() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        String to = Bank.firstAccountNumber(this, other);
        assertField(propose(s.accessToken(), "TELEPORT", Map.of()), "kind");
        assertField(propose(s.accessToken(), "SELF_DESTRUCT", Map.of()), "kind");
        assertField(propose(s.accessToken(), "TRANSFER", Map.of("fromAccountId", from.toString(), "toAccountNumber", to,
                "amount", 2500)), "payload.amount");
        assertField(propose(s.accessToken(), "TRANSFER", Map.of("fromAccountId", from.toString(), "toAccountNumber", to,
                "amount", "-5.00")), "payload.amount");
        assertField(propose(s.accessToken(), "TRANSFER", Map.of("fromAccountId", Bank.firstAccountId(this, other)
                .toString(), "toAccountNumber", to, "amount", "5.00")), "payload.fromAccountId");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intents WHERE user_id = ?", Long.class, s.userId())).isZero();
    }

    @Test
    void goalOrderAndFdIntentsRunThroughTheirServices() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "50000.00");
        String goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Goa trip", "target", "20000.00"),
                Bank.auth(s)).jsonBody().get("id").asString();

        JsonNode g = propose(s.accessToken(), "GOAL_TRANSFER", Map.of("fromAccountId", from.toString(),
                "toAccountId", goal, "amount", "1500.00")).jsonBody();
        assertThat(g.get("summary").get("title").asString()).isEqualTo("Move ₹1,500.00 to Goa trip");
        assertThat(g.get("requiresStepUp").asBoolean()).isFalse();
        assertThat(approve(s, g.get("id").asString(), UUID.randomUUID(), false).jsonBody().get("status").asString())
                .isEqualTo("EXECUTED");
        assertThat(Bank.balance(this, s, UUID.fromString(goal))).isEqualTo("1500.00");

        String symbol = jdbc.queryForObject("SELECT symbol FROM instruments ORDER BY price LIMIT 1", String.class);
        JsonNode o = propose(s.accessToken(), "ORDER", Map.of("accountId", from.toString(), "symbol", symbol,
                "side", "BUY", "quantity", 2)).jsonBody();
        assertThat(o.get("summary").get("title").asString()).isEqualTo("Buy 2 × " + symbol);
        JsonNode executed = approve(s, o.get("id").asString(), UUID.randomUUID(), true).jsonBody();
        assertThat(executed.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(executed.get("resultRef").get("type").asString()).isEqualTo("ORDER");
        assertThat(get("/api/v1/portfolio", s.accessToken()).body()).contains(symbol);

        JsonNode fd = propose(s.accessToken(), "FD_BOOKING", Map.of("fromAccountId", from.toString(),
                "amount", "10000.00", "tenureDays", 365, "onMaturity", "PAYOUT")).jsonBody();
        assertThat(fd.get("summary").get("lines").toString()).contains("7% p.a.").contains("₹10,700.00, paid out");
        JsonNode booked = approve(s, fd.get("id").asString(), UUID.randomUUID(), true).jsonBody();
        assertThat(booked.get("resultRef").get("type").asString()).isEqualTo("FIXED_DEPOSIT");
        assertThat(get("/api/v1/fixed-deposits", s.accessToken()).body()).contains(booked.get("resultRef").get("id").asString());
    }

    @Test
    void scheduledTransferIntentsCreateAnInstructionAndMoveNothingYet() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "100000.00");
        String handle = handle(friend);
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Kolkata")).plusDays(2);
        while (start.getDayOfMonth() > 28) {
            start = start.plusDays(1);
        }

        assertField(propose(s.accessToken(), "SCHEDULED_TRANSFER", Map.of("fromAccountId", from.toString(),
                "toHandle", handle, "amount", "500.00", "startDate", LocalDate.now(ZoneId.of("Asia/Kolkata")).toString(),
                "frequency", "ONCE")), "payload.startDate");
        assertField(propose(s.accessToken(), "SCHEDULED_TRANSFER", Map.of("fromAccountId", from.toString(),
                "toHandle", handle, "amount", "500.00", "startDate", start.toString(), "frequency", "DAILY")),
                "payload.frequency");

        JsonNode intent = propose(s.accessToken(), "SCHEDULED_TRANSFER", Map.of("fromAccountId", from.toString(),
                "toHandle", handle.toUpperCase(), "amount", "60000.00", "note", "Rent", "startDate", start.toString(),
                "frequency", "MONTHLY", "count", 3)).jsonBody();
        assertThat(intent.get("summary").get("title").asString())
                .isEqualTo("Set up a repeating payment of ₹60,000.00 to Test U.");
        assertThat(intent.get("summary").get("lines").toString()).contains("Every month on day " + start.getDayOfMonth())
                .contains("After 3 payments").contains("₹60,000.00 each time").contains("Rent");
        assertThat(intent.get("riskLevel").asString()).isEqualTo("HIGH");
        assertThat(intent.get("requiresStepUp").asBoolean()).isTrue();
        String id = intent.get("id").asString();
        assertThat(approve(s, id, UUID.randomUUID(), false).status()).isEqualTo(401);

        // The INTENT_APPROVE step-up is enough; the service doesn't ask again for a TRANSFER one.
        JsonNode done = approve(s, id, UUID.randomUUID(), true).jsonBody();
        assertThat(done.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(done.get("resultRef").get("type").asString()).isEqualTo("SCHEDULED_TRANSFER");
        JsonNode st = get("/api/v1/scheduled-transfers", s.accessToken()).jsonBody().get(0);
        assertThat(st.get("id").asString()).isEqualTo(done.get("resultRef").get("id").asString());
        assertThat(st.get("amount").asString()).isEqualTo("60000.00");
        assertThat(st.get("remainingCount").asInt()).isEqualTo(3);
        assertThat(st.get("nextRunDate").asString()).isEqualTo(start.toString());
        assertThat(Bank.balance(this, s, from)).isEqualTo("100000.00");

        // Between your own accounts, below ₹50,000: no step-up.
        UUID second = UUID.fromString(postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(s)).jsonBody()
                .get("id").asString());
        JsonNode own = propose(s.accessToken(), "SCHEDULED_TRANSFER", Map.of("fromAccountId", from.toString(),
                "toAccountNumber", jdbc.queryForObject("SELECT account_number FROM accounts WHERE id = ?", String.class,
                        second), "amount", "100.00", "startDate", start.toString(), "frequency", "ONCE")).jsonBody();
        assertThat(own.get("requiresStepUp").asBoolean()).isFalse();
        assertThat(own.get("riskLevel").asString()).isEqualTo("LOW");
        assertThat(approve(s, own.get("id").asString(), UUID.randomUUID(), false).jsonBody().get("status").asString())
                .isEqualTo("EXECUTED");
    }

    @Test
    void paymentRequestIntentsAskAndMoveNoMoney() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        String friendHandle = handle(friend);
        Map<String, Object> payload = Map.of("toHandle", friendHandle, "amount", "750.00", "note", "Movie tickets");

        Response noHandle = propose(s.accessToken(), "PAYMENT_REQUEST", payload);
        assertThat(noHandle.status()).isEqualTo(409);
        String mine = handle(s);
        assertField(propose(s.accessToken(), "PAYMENT_REQUEST", Map.of("toHandle", mine, "amount", "1.00")),
                "payload.toHandle");
        assertField(propose(s.accessToken(), "PAYMENT_REQUEST", Map.of("toHandle", "nobody.here.x@qg", "amount", "1.00")),
                "payload.toHandle");

        JsonNode intent = propose(s.accessToken(), "PAYMENT_REQUEST", payload).jsonBody();
        assertThat(intent.get("summary").get("title").asString()).isEqualTo("Ask Test U. for ₹750.00");
        assertThat(intent.get("requiresStepUp").asBoolean()).isFalse();
        assertThat(intent.get("riskLevel").asString()).isEqualTo("LOW");
        JsonNode done = approve(s, intent.get("id").asString(), UUID.randomUUID(), false).jsonBody();
        assertThat(done.get("resultRef").get("type").asString()).isEqualTo("PAYMENT_REQUEST");
        JsonNode incoming = get("/api/v1/payment-requests", friend.accessToken()).jsonBody().get("content").get(0);
        assertThat(incoming.get("id").asString()).isEqualTo(done.get("resultRef").get("id").asString());
        assertThat(incoming.get("amount").asString()).isEqualTo("750.00");
        assertThat(incoming.get("fromHandle").asString()).isEqualTo(mine);
    }

    @Test
    void billPaymentIntentsPayThePinnedBillExactlyOnce() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "20000.00");
        String power = "b1000000-0000-4000-8000-000000000001";

        assertField(propose(s.accessToken(), "BILL_PAYMENT", Map.of("fromAccountId", from.toString(),
                "billerId", power, "consumerNumber", "12")), "payload.consumerNumber");
        assertField(propose(s.accessToken(), "BILL_PAYMENT", Map.of("fromAccountId", from.toString(),
                "billerId", UUID.randomUUID().toString(), "consumerNumber", "9876543210")), "payload.billerId");

        JsonNode intent = propose(s.accessToken(), "BILL_PAYMENT", Map.of("fromAccountId", from.toString(),
                "billerId", power, "consumerNumber", "9876543210")).jsonBody();
        BigDecimal amount = new BigDecimal(jdbc.queryForObject("SELECT amount FROM bills WHERE user_id = ?",
                BigDecimal.class, s.userId()).toPlainString());
        assertThat(intent.get("summary").get("title").asString())
                .isEqualTo("Pay " + IntentKinds.inr(amount) + " to Metro Power Distribution");
        assertThat(intent.get("summary").get("lines").toString()).contains("••••3210").doesNotContain("9876543210");
        assertThat(intent.get("requiresStepUp").asBoolean()).isTrue();
        String id = intent.get("id").asString();

        // Two approvals at once (a double tap with fresh keys): one pays, the other finds it decided.
        CompletableFuture<Response> a = CompletableFuture.supplyAsync(() -> approve(s, id, UUID.randomUUID(), true));
        CompletableFuture<Response> b = CompletableFuture.supplyAsync(() -> approve(s, id, UUID.randomUUID(), true));
        assertThat(List.of(a.get().status(), b.get().status())).containsExactlyInAnyOrder(200, 409);
        JsonNode done = (a.get().status() == 200 ? a.get() : b.get()).jsonBody();
        assertThat(done.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(done.get("resultRef").get("type").asString()).isEqualTo("BILL_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bill_payments WHERE user_id = ?", Integer.class, s.userId()))
                .isEqualTo(1);
        assertThat(new BigDecimal(Bank.balance(this, s, from))).isEqualByComparingTo(new BigDecimal("20000.00")
                .subtract(amount));

        // This month's bill is paid, so proposing it again is refused up front.
        assertThat(propose(s.accessToken(), "BILL_PAYMENT", Map.of("fromAccountId", from.toString(),
                "billerId", power, "consumerNumber", "9876543210")).status()).isEqualTo(409);
    }

    @Test
    void loanApplicationIntentsApplyButNeverDisburse() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID to = Bank.firstAccountId(this, s);

        Response ineligible = propose(s.accessToken(), "LOAN_APPLICATION", Map.of("amount", "500000.00",
                "tenureMonths", 12, "monthlyIncome", "10000.00", "purpose", "New bike", "disburseAccountId", to.toString()));
        assertThat(ineligible.status()).isEqualTo(422);
        assertThat(ineligible.jsonBody().get("code").asString()).isEqualTo("LOAN_NOT_ELIGIBLE");
        assertField(propose(s.accessToken(), "LOAN_APPLICATION", Map.of("amount", "50000.00", "tenureMonths", 100,
                "monthlyIncome", "80000.00", "purpose", "New bike", "disburseAccountId", to.toString())),
                "payload.tenureMonths");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM intents WHERE user_id = ?", Long.class, s.userId())).isZero();

        JsonNode intent = propose(s.accessToken(), "LOAN_APPLICATION", Map.of("amount", "50000.00", "tenureMonths", 12,
                "monthlyIncome", "80000.00", "purpose", "New bike", "disburseAccountId", to.toString())).jsonBody();
        assertThat(intent.get("summary").get("title").asString()).isEqualTo("Apply for a loan of ₹50,000.00");
        assertThat(intent.get("summary").get("lines").toString()).contains("12 months").contains("12% p.a.")
                .contains("New bike").contains("nothing is paid out until it's approved");
        assertThat(intent.get("requiresStepUp").asBoolean()).isTrue();
        JsonNode done = approve(s, intent.get("id").asString(), UUID.randomUUID(), true).jsonBody();
        assertThat(done.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(done.get("resultRef").get("type").asString()).isEqualTo("LOAN");
        JsonNode loan = get("/api/v1/loans", s.accessToken()).jsonBody().get(0);
        assertThat(loan.get("id").asString()).isEqualTo(done.get("resultRef").get("id").asString());
        assertThat(loan.get("status").asString()).isEqualTo("PENDING");
        assertThat(Bank.balance(this, s, to)).isEqualTo("0.00");
    }

    private String handle(TestUsers.Session s) {
        String h = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@qg";
        assertThat(put("/api/v1/users/me/handle", Map.of("handle", h, "accountId", Bank.firstAccountId(this, s)
                .toString()), s.accessToken()).status()).isEqualTo(200);
        return h;
    }

    @Test
    void indianDigitGrouping() {
        assertThat(IntentKinds.inr(new java.math.BigDecimal("1234567.5"))).isEqualTo("₹12,34,567.50");
        assertThat(IntentKinds.inr(new java.math.BigDecimal("999"))).isEqualTo("₹999.00");
        assertThat(IntentKinds.inr(new java.math.BigDecimal("100000"))).isEqualTo("₹1,00,000.00");
    }

    private static void assertField(Response res, String field) {
        assertThat(res.status()).as(res.body()).isEqualTo(400);
        assertThat(res.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo(field);
    }
}
