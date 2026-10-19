package com.quantgen.intent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
        assertField(propose(s.accessToken(), "BILL_PAYMENT", Map.of()), "kind");
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
