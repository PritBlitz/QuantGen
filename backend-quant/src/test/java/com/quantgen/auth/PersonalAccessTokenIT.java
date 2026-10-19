package com.quantgen.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M10 personal access tokens (PRD 12.12, F-MCP-2). */
class PersonalAccessTokenIT extends IntegrationTest {

    @org.junit.jupiter.api.BeforeEach
    void developerAccessOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'mcp'");
    }

    @org.junit.jupiter.api.AfterEach
    void developerAccessOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'mcp'");
    }

    private Response create(TestUsers.Session s, String name, String scope, int days) {
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "TOKEN_CREATE"));
        return postJson("/api/v1/users/me/tokens", Map.of("name", name, "scope", scope, "expiresInDays", days), h);
    }

    @Test
    void aTokenIsShownOnceStoredHashedAndNeedsStepUp() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Response denied = postJson("/api/v1/users/me/tokens", Map.of("name", "Claude", "scope", "read",
                "expiresInDays", 30), Bank.auth(s));
        assertThat(denied.status()).isEqualTo(401);
        assertThat(denied.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        Response res = create(s, "Claude Desktop", "read", 30);
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.header("Cache-Control")).contains("no-store");
        String token = res.jsonBody().get("token").asString();
        assertThat(token).startsWith("qg_pat_").hasSize(50);
        assertThat(res.jsonBody().get("scope").asString()).isEqualTo("read");

        JsonNode list = get("/api/v1/users/me/tokens", s.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).has("token")).isFalse();
        assertThat(list.get(0).get("lastUsedAt").isNull()).isTrue();
        assertThat(list.toString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM personal_access_tokens WHERE token_hash = ?", Long.class,
                token)).isZero(); // only a hash is stored

        assertThat(create(s, "x", "write", 30).status()).isEqualTo(400);
        assertThat(create(s, "x", "read", 91).status()).isEqualTo(400);
        assertThat(create(s, "x", "read", 0).status()).isEqualTo(400);
        assertThat(create(s, " ", "read", 30).status()).isEqualTo(400);
    }

    @Test
    void aReadTokenReadsOwnDataAndNothingElseAndEveryUseIsAudited() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "500.00");
        JsonNode created = create(s, "MCP", "read", 7).jsonBody();
        String pat = created.get("token").asString();

        assertThat(get("/api/v1/accounts", pat).jsonBody().get(0).get("balance").asString()).isEqualTo("500.00");
        assertThat(get("/api/v1/accounts/" + acc + "/transactions", pat).status()).isEqualTo(200);
        assertThat(get("/api/v1/users/me", pat).jsonBody().get("id").asString()).isEqualTo(s.userId().toString());
        // Someone else's account is still 404 (ownership checks are unchanged).
        assertThat(get("/api/v1/accounts/" + Bank.firstAccountId(this, other), pat).status()).isEqualTo(404);

        // Deny by default: writes, secrets, token management, admin.
        Map<String, String> h = Map.of("Authorization", "Bearer " + pat, "Idempotency-Key", UUID.randomUUID().toString());
        assertThat(postJson("/api/v1/transfers", Map.of("fromAccountId", acc.toString(), "toAccountNumber",
                Bank.firstAccountNumber(this, other), "amount", "10.00"), h).status()).isEqualTo(403);
        assertThat(get("/api/v1/users/me/tokens", pat).status()).isEqualTo(403);
        assertThat(postJson("/api/v1/users/me/tokens", Map.of("name", "x", "scope", "read", "expiresInDays", 1), h)
                .status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/users", pat).status()).isEqualTo(403);
        assertThat(get("/api/v1/users/me/sessions", pat).status()).isEqualTo(403);
        assertThat(postJson("/api/v1/intents", Map.of(), h).status()).isEqualTo(403); // read scope can't propose
        assertThat(Bank.balance(this, s, acc)).isEqualTo("500.00");

        String id = created.get("id").asString();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_USED' AND target_id = ?",
                Long.class, id)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'TOKEN_DENIED' AND target_id = ?",
                Long.class, id)).isEqualTo(6);
        assertThat(get("/api/v1/users/me/tokens", s.accessToken()).jsonBody().get(0).get("lastUsedAt").isNull()).isFalse();
    }

    @Test
    void aProposeTokenGetsPastTheFilterForIntents() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String pat = create(s, "Agent", "read+propose", 30).jsonBody().get("token").asString();
        Response res = postJson("/api/v1/intents", Map.of(), Map.of("Authorization", "Bearer " + pat));
        assertThat(res.status()).isNotIn(401, 403); // allowed by scope; the endpoint itself arrives with V2-M4
    }

    @Test
    void revokedExpiredAndUnknownTokensAre401() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        JsonNode created = create(s, "Old", "read", 30).jsonBody();
        String pat = created.get("token").asString();
        String id = created.get("id").asString();

        assertThat(delete("/api/v1/users/me/tokens/" + id, other.accessToken()).status()).isEqualTo(404);
        assertThat(delete("/api/v1/users/me/tokens/" + id, s.accessToken()).status()).isEqualTo(204);
        assertThat(get("/api/v1/accounts", pat).status()).isEqualTo(401);
        assertThat(delete("/api/v1/users/me/tokens/" + id, s.accessToken()).status()).isEqualTo(404);
        assertThat(get("/api/v1/users/me/tokens", s.accessToken()).jsonBody().size()).isZero();

        String expiring = create(s, "Soon", "read", 1).jsonBody().get("token").asString();
        jdbc.update("UPDATE personal_access_tokens SET created_at = now() - interval '2 days', "
                + "expires_at = now() - interval '1 day' WHERE user_id = ? AND name = 'Soon'", s.userId());
        assertThat(get("/api/v1/accounts", expiring).status()).isEqualTo(401);
        assertThat(get("/api/v1/accounts", "qg_pat_" + "x".repeat(43)).status()).isEqualTo(401);
    }

    @Test
    void theMcpFlagIsAKillSwitchForCreatingAndUsingTokens() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String pat = create(s, "Live", "read", 7).jsonBody().get("token").asString();
        developerAccessOff();
        Response use = get("/api/v1/accounts", pat);
        assertThat(use.status()).isEqualTo(503);
        assertThat(use.jsonBody().get("code").asString()).isEqualTo("FEATURE_DISABLED");
        assertThat(create(s, "New", "read", 7).status()).isEqualTo(503);
        assertThat(get("/api/v1/users/me/tokens", s.accessToken()).status()).isEqualTo(200); // still listable and revocable
        developerAccessOn();
        assertThat(get("/api/v1/accounts", pat).status()).isEqualTo(200);
    }

    @Test
    void atMostTenActiveTokensAndNoneForAdmins() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        for (int i = 0; i < 10; i++) {
            assertThat(create(s, "t" + i, "read", 30).status()).isEqualTo(201);
        }
        Response eleventh = create(s, "t10", "read", 30);
        assertThat(eleventh.status()).isEqualTo(422);
        assertThat(eleventh.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");

        TestUsers.Session admin = Bank.admin(this);
        assertThat(create(admin, "admin", "read", 30).status()).isEqualTo(403);
    }
}
