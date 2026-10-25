package com.quantgen.copilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.FakeAgent;
import com.quantgen.support.FakeAgent.Answer;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M4 copilot, core side: conversations, the relayed stream, delegated tokens, budgets and traces. */
class CopilotIT extends IntegrationTest {

    static FakeAgent agent;

    @BeforeAll
    static void startAgent() throws Exception {
        agent = new FakeAgent();
    }

    @AfterAll
    static void stopAgent() {
        agent.close();
    }

    @BeforeEach
    void aiOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name IN ('ai', 'autopilot')");
    }

    @AfterEach
    void aiOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name IN ('ai', 'autopilot')");
    }

    private String conversation(TestUsers.Session s) {
        Response res = postJson("/api/v1/copilot/conversations", "", Bank.auth(s));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return res.jsonBody().get("id").asString();
    }

    private Response say(TestUsers.Session s, String conversationId, String text) {
        return postJson("/api/v1/copilot/conversations/" + conversationId + "/messages", Map.of("text", text),
                Bank.auth(s));
    }

    private Response asAgent(String token, String method, String path, Object body) {
        var b = request(path).header("Authorization", "Bearer " + token).header("Content-Type", "application/json");
        return send(("GET".equals(method) ? b.GET() : b.POST(java.net.http.HttpRequest.BodyPublishers.ofString(
                body instanceof String str ? str : json.writeValueAsString(body)))).build());
    }

    @Test
    void aTurnRelaysTheAgentAndTheDelegatedTokenOnlyReadsAndProposes() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID from = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, from, "5000.00");
        String conv = conversation(s);
        AtomicReference<String> seen = new AtomicReference<>();

        agent.onTurn(req -> {
            String token = req.get("token").asString();
            seen.set(token);
            // Reads work, as the user; money movement, approvals, secrets and admin don't.
            assertThat(asAgent(token, "GET", "/api/v1/accounts", null).status()).isEqualTo(200);
            assertThat(asAgent(token, "POST", "/api/v1/transfers", Map.of("fromAccountId", from.toString(),
                    "toAccountNumber", Bank.firstAccountNumber(this, friend), "amount", "1.00")).status()).isEqualTo(403);
            assertThat(asAgent(token, "GET", "/api/v1/users/me/sessions", null).status()).isEqualTo(403);
            assertThat(asAgent(token, "GET", "/api/v1/admin/users", null).status()).isEqualTo(403);
            JsonNode intent = asAgent(token, "POST", "/api/v1/intents", Map.of("kind", "TRANSFER", "agent", "PAYMENTS",
                    "rationale", "You asked to pay your friend.", "conversationId", UUID.randomUUID().toString(),
                    "payload", Map.of("fromAccountId", from.toString(), "toAccountNumber",
                            Bank.firstAccountNumber(this, friend), "amount", "500.00"))).jsonBody();
            assertThat(asAgent(token, "POST", "/api/v1/intents/" + intent.get("id").asString() + "/approve", "")
                    .status()).isEqualTo(403);
            return Answer.sse(FakeAgent.event("tool", "{\"agent\":\"PAYMENTS\",\"tool\":\"propose_intent\",\"status\":\"STARTED\"}")
                    + FakeAgent.event("step", "{\"agent\":\"PAYMENTS\",\"kind\":\"TOOL\",\"name\":\"propose_intent\","
                    + "\"status\":\"OK\",\"latencyMs\":12,\"detail\":\"TRANSFER ••••1234\"}")
                    + FakeAgent.event("intent", "{\"id\":\"" + intent.get("id").asString() + "\"}")
                    + FakeAgent.event("citation", "{\"type\":\"ACCOUNT\",\"id\":\"" + from + "\",\"label\":\"Savings\"}")
                    + FakeAgent.event("citation", "{\"type\":\"<script>\",\"id\":\"x\",\"label\":\"bad\"}")
                    + FakeAgent.event("token", "{\"text\":\"Here's a payment \"}")
                    + FakeAgent.event("token", "{\"text\":\"for you to approve.\"}")
                    + FakeAgent.event("done", "{\"usage\":{\"inputTokens\":1200,\"outputTokens\":80}}"));
        });

        Response turn = say(s, conv, "Pay my friend ₹500\nfor dinner");
        assertThat(turn.status()).as(turn.body()).isEqualTo(200);
        assertThat(turn.raw().headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        String stream = turn.body();
        assertThat(stream).contains("event:token").contains("for you to approve.").contains("event:intent")
                .contains("\"title\":\"Send ₹500.00 to Test U.\"").contains("event:citation")
                .doesNotContain("<script>").contains("event:done").contains("\"inputTokens\":1200");
        assertThat(agent.lastSecret()).isEqualTo("test-only-agent-secret");
        // The agent token is not an access token.
        assertThat(get("/api/v1/users/me", seen.get()).status()).isEqualTo(200); // 🤖 read route
        assertThat(get("/api/v1/copilot/conversations", seen.get()).status()).isEqualTo(403);

        JsonNode detail = get("/api/v1/copilot/conversations/" + conv, s.accessToken()).jsonBody();
        assertThat(detail.get("title").asString()).isEqualTo("Pay my friend ₹500 for dinner");
        assertThat(detail.get("messages").size()).isEqualTo(2);
        JsonNode answer = detail.get("messages").get(1);
        assertThat(answer.get("role").asString()).isEqualTo("ASSISTANT");
        assertThat(answer.get("text").asString()).isEqualTo("Here's a payment for you to approve.");
        assertThat(answer.get("intents").get(0).get("proposedBy").get("agent").asString()).isEqualTo("PAYMENTS");
        // The agent token pins the intent to its own conversation, whatever the body said.
        assertThat(answer.get("intents").get(0).get("conversationId").asString()).isEqualTo(conv);
        assertThat(answer.get("citations").size()).isEqualTo(1);

        String runId = answer.get("runId").asString();
        JsonNode trace = get("/api/v1/copilot/runs/" + runId + "/trace", s.accessToken()).jsonBody();
        assertThat(trace.get("steps").get(0).get("name").asString()).isEqualTo("propose_intent");
        assertThat(trace.get("steps").get(0).get("detail").isNull()).isTrue();
        assertThat(get("/api/v1/copilot/runs/" + runId + "/trace", friend.accessToken()).status()).isEqualTo(404);
        TestUsers.Session admin = Bank.admin(this);
        assertThat(get("/api/v1/admin/ai/runs/" + runId, admin.accessToken()).jsonBody().get("steps").get(0)
                .get("detail").asString()).isEqualTo("TRANSFER ••••1234");
        JsonNode usage = get("/api/v1/admin/ai/usage", admin.accessToken()).jsonBody();
        assertThat(usage.get("tokens").asLong()).isGreaterThanOrEqualTo(1280);
        assertThat(usage.get("costUsd").asString()).isEqualTo("0.00");
        assertThat(usage.get("byAgent").toString()).contains("PAYMENTS");
        assertThat(get("/api/v1/admin/ai/usage", s.accessToken()).status()).isEqualTo(403);
    }

    @Test
    void errorsBeforeTheStreamAreJsonAndAMidStreamErrorStillStoresBothMessages() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        String conv = conversation(s);

        // Agent not set up (503) → JSON, nothing stored.
        agent.onTurn(req -> new Answer(503, "application/json", "{}"));
        Response down = say(s, conv, "hello");
        assertThat(down.status()).isEqualTo(503);
        assertThat(down.jsonBody().get("code").asString()).isEqualTo("AI_UNAVAILABLE");
        assertThat(get("/api/v1/copilot/conversations/" + conv, s.accessToken()).jsonBody().get("messages").size())
                .isZero();

        assertThat(say(other, conv, "hello").status()).isEqualTo(404);
        assertThat(say(s, conv, "bad\u0007text").status()).isEqualTo(400);

        agent.onTurn(req -> Answer.sse(FakeAgent.event("token", "{\"text\":\"Let me check\"}")
                + FakeAgent.event("error", "{\"code\":\"AI_BUSY\",\"message\":\"429 from provider\"}")));
        String stream = say(s, conv, "how much did I spend?").body();
        assertThat(stream).contains("event:error").contains("AI_BUSY").contains("busy right now")
                .doesNotContain("429 from provider"); // the provider's text never reaches the browser
        JsonNode messages = get("/api/v1/copilot/conversations/" + conv, s.accessToken()).jsonBody().get("messages");
        assertThat(messages.size()).isEqualTo(2);
        assertThat(messages.get(1).get("text").asString()).isEqualTo("Let me check");

        // The daily token budget.
        jdbc.update("INSERT INTO agent_runs (user_id, kind, status, input_tokens, output_tokens) VALUES (?, 'CHAT', 'OK', "
                + "300000, 0)", s.userId());
        Response broke = say(s, conv, "again");
        assertThat(broke.status()).isEqualTo(429);
        assertThat(broke.jsonBody().get("code").asString()).isEqualTo("BUDGET_EXHAUSTED");

        // Off switch, then delete.
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'ai'");
        assertThat(say(s, conv, "hello").jsonBody().get("code").asString()).isEqualTo("FEATURE_DISABLED");
        assertThat(delete("/api/v1/copilot/conversations/" + conv, other.accessToken()).status()).isEqualTo(404);
        assertThat(delete("/api/v1/copilot/conversations/" + conv, s.accessToken()).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM copilot_messages WHERE conversation_id = ?::uuid",
                Long.class, conv)).isZero();
    }

    @Test
    void explainThisRunSendsTheServersOwnFactsAboutTheRun() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        String conv = conversation(s);
        // A finished run of the user's own rule (inserted directly: the engine is tested elsewhere).
        UUID ruleId = jdbc.queryForObject("""
                INSERT INTO autopilot_rules (user_id, name, version, status, trigger_type) VALUES (?, 'Rent', 1, 'ACTIVE',
                'SCHEDULE') RETURNING id""", UUID.class, s.userId());
        jdbc.update("INSERT INTO autopilot_rule_versions (rule_id, version, dsl) VALUES (?, 1, '{}')", ruleId);
        UUID runId = jdbc.queryForObject("""
                INSERT INTO autopilot_runs (rule_id, user_id, rule_version, trigger_event_id, trigger_summary, planned_total,
                                            outcome, finished_at)
                VALUES (?, ?, 1, 'schedule:2026-11-01', 'Scheduled: 1st of the month', 12000, 'FAILED', now()) RETURNING id
                """, UUID.class, ruleId, s.userId());
        jdbc.update("INSERT INTO autopilot_run_actions (run_id, idx, type, amount, status, error) VALUES (?, 0, "
                + "'TRANSFER', 12000, 'FAILED', 'Insufficient funds')", runId);
        AtomicReference<String> sent = new AtomicReference<>();
        agent.onTurn(req -> {
            sent.set(req.get("text").asString());
            return Answer.sse(FakeAgent.event("token", "{\"text\":\"Your account was short.\"}")
                    + FakeAgent.event("done", "{\"usage\":{\"inputTokens\":1,\"outputTokens\":1}}"));
        });
        Response ok = postJson("/api/v1/copilot/conversations/" + conv + "/messages", Map.of("text", "Why did this fail?",
                "context", Map.of("type", "AUTOPILOT_RUN", "id", runId.toString())), Bank.auth(s));
        assertThat(ok.body()).contains("Your account was short.");
        assertThat(sent.get()).startsWith("Why did this fail?").contains("Autopilot rule \"Rent\"")
                .contains("Scheduled: 1st of the month").contains("FAILED").contains("Insufficient funds");
        // Stored as the user typed it; someone else's run is 404.
        assertThat(get("/api/v1/copilot/conversations/" + conv, s.accessToken()).jsonBody().get("messages").get(0)
                .get("text").asString()).isEqualTo("Why did this fail?");
        String otherConv = conversation(other);
        assertThat(postJson("/api/v1/copilot/conversations/" + otherConv + "/messages", Map.of("text", "Why?",
                "context", Map.of("type", "AUTOPILOT_RUN", "id", runId.toString())), Bank.auth(other)).status())
                .isEqualTo(404);
    }

    @Test
    void compileValidatesThePlannersDraftLikeAnyRule() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String savings = Bank.firstAccountId(this, s).toString();
        String goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Goa", "target", "50000.00"),
                Bank.auth(s)).jsonBody().get("id").asString();
        agent.onCompile(req -> Answer.json("{\"rule\":{\"dslVersion\":1,\"trigger\":{\"type\":\"SCHEDULE\","
                + "\"frequency\":\"MONTHLY\",\"dayOfMonth\":1},\"actions\":[{\"type\":\"TRANSFER\",\"fromAccountId\":\""
                + savings + "\",\"toAccountId\":\"" + goal + "\",\"amount\":{\"mode\":\"FIXED\",\"value\":\"2000.00\"}}],"
                + "\"caps\":{\"perRun\":\"2000.00\",\"perMonth\":\"2000.00\"}},\"warnings\":[\"I assumed savings\"],"
                + "\"steps\":[{\"agent\":\"PLANNER\",\"kind\":\"MODEL\",\"name\":\"gemini\",\"status\":\"OK\"}],"
                + "\"usage\":{\"inputTokens\":500,\"outputTokens\":90}}"));
        JsonNode ok = postJson("/api/v1/autopilot/compile", Map.of("text", "Move 2000 to Goa on the 1st"), Bank.auth(s))
                .jsonBody();
        assertThat(ok.get("rendering").asString()).startsWith("On the 1st of every month at 08:00 IST: move ₹2,000.00");
        assertThat(ok.get("warnings").get(0).asString()).isEqualTo("I assumed savings");

        // A draft that points at someone else's account is refused like any rule.
        agent.onCompile(req -> Answer.json("{\"rule\":{\"dslVersion\":1,\"trigger\":{\"type\":\"SCHEDULE\","
                + "\"frequency\":\"MONTHLY\",\"dayOfMonth\":1},\"actions\":[{\"type\":\"TRANSFER\",\"fromAccountId\":\""
                + UUID.randomUUID() + "\",\"toAccountId\":\"" + goal + "\",\"amount\":{\"mode\":\"FIXED\",\"value\":\"1.00\"}}],"
                + "\"caps\":{\"perRun\":\"1.00\",\"perMonth\":\"1.00\"}},\"usage\":{\"inputTokens\":1,\"outputTokens\":1}}"));
        Response bad = postJson("/api/v1/autopilot/compile", Map.of("text", "Move money somewhere please"), Bank.auth(s));
        assertThat(bad.status()).isEqualTo(422);
        assertThat(bad.jsonBody().get("code").asString()).isEqualTo("RULE_INVALID");
    }
}
