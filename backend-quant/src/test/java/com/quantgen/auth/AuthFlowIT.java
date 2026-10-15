package com.quantgen.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M1: register, login, refresh rotation and reuse detection, logout, /users/me, lockout. */
class AuthFlowIT extends IntegrationTest {

    @Test
    void registerCreatesCustomerWithSavingsAccountAndNoTokens() {
        String email = TestUsers.uniqueEmail();
        Response res = TestUsers.register(this, email.toUpperCase());

        assertThat(res.status()).isEqualTo(201);
        JsonNode user = res.jsonBody();
        assertThat(user.get("email").asString()).isEqualTo(email); // normalized to lower case
        assertThat(user.get("role").asString()).isEqualTo("CUSTOMER");
        assertThat(user.get("fullName").asString()).isEqualTo("Test User");
        assertThat(user.get("id").asString()).hasSize(36);
        assertThat(user.get("createdAt").asString()).endsWith("Z");
        assertThat(user.has("password")).isFalse();
        assertThat(user.has("passwordHash")).isFalse();
        assertThat(res.body()).doesNotContain(TestUsers.PASSWORD);
        assertThat(TestUsers.setCookieHeader(res)).isNull();

        List<Map<String, Object>> accounts = jdbc.queryForList(
                "SELECT a.type, a.status, a.account_number, la.balance FROM accounts a "
                        + "JOIN ledger_accounts la ON la.id = a.id WHERE a.user_id = ?::uuid",
                user.get("id").asString());
        assertThat(accounts).hasSize(1);
        assertThat(accounts.getFirst().get("type")).isEqualTo("SAVINGS");
        assertThat(accounts.getFirst().get("status")).isEqualTo("ACTIVE");
        assertThat((String) accounts.getFirst().get("account_number")).matches("[0-9]{12}");

        String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
        assertThat(hash).startsWith("$2"); // BCrypt (cost is lowered in the test profile)
    }

    @Test
    void duplicateEmailIs409WithEmailFieldError() {
        String email = TestUsers.uniqueEmail();
        assertThat(TestUsers.register(this, email).status()).isEqualTo(201);

        Response dup = TestUsers.register(this, email);
        assertThat(dup.status()).isEqualTo(409);
        assertThat(dup.jsonBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(dup.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("email");
    }

    @Test
    void invalidRegistrationReportsEachField() {
        Response res = postJson("/api/v1/auth/register",
                Map.of("fullName", "", "email", "nope", "phone", "12", "password", "weak"), Map.of());
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        List<String> fields = new ArrayList<>();
        res.jsonBody().get("fieldErrors").forEach(f -> fields.add(f.get("field").asString()));
        assertThat(fields).contains("fullName", "email", "phone", "password");
        assertThat(res.body()).doesNotContain("weak");
    }

    @Test
    void loginReturnsTokenAndSecureRefreshCookie() {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(this, email);

        Response res = postJson("/api/v1/auth/login", Map.of("email", email, "password", TestUsers.PASSWORD), Map.of());
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.jsonBody().get("expiresIn").asLong()).isEqualTo(900);
        assertThat(res.jsonBody().get("accessToken").asString()).isNotBlank();
        assertThat(res.jsonBody().get("user").get("email").asString()).isEqualTo(email);

        String cookie = TestUsers.setCookieHeader(res);
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/v1/auth", "Max-Age=604800");
    }

    @Test
    void wrongPasswordAndUnknownEmailAreIndistinguishable401s() {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(this, email);

        Response wrong = postJson("/api/v1/auth/login", Map.of("email", email, "password", "Wrong!Pass1"), Map.of());
        Response unknown = postJson("/api/v1/auth/login",
                Map.of("email", TestUsers.uniqueEmail(), "password", "Wrong!Pass1"), Map.of());
        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.jsonBody().get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(wrong.jsonBody().get("message").asString()).isEqualTo(unknown.jsonBody().get("message").asString());
    }

    @Test
    void usersMeRequiresValidBearerToken() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        Response me = get("/api/v1/users/me", s.accessToken());
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.jsonBody().get("id").asString()).isEqualTo(s.userId().toString());
        assertThat(me.jsonBody().get("email").asString()).isEqualTo(s.email());

        assertThat(get("/api/v1/users/me").status()).isEqualTo(401);
        assertThat(get("/api/v1/users/me", s.accessToken() + "x").status()).isEqualTo(401);
    }

    @Test
    void customerCannotReachAdminEndpoints() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Response res = get("/api/v1/admin/stats", s.accessToken());
        assertThat(res.status()).isEqualTo(403);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("FORBIDDEN");
    }

    @Test
    void refreshRotatesTheCookie() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        Response refreshed = refresh(s.refreshCookie());
        assertThat(refreshed.status()).isEqualTo(200);
        assertThat(refreshed.jsonBody().get("user").get("id").asString()).isEqualTo(s.userId().toString());
        String rotated = TestUsers.refreshCookieValue(refreshed);
        assertThat(rotated).isNotBlank().isNotEqualTo(s.refreshCookie());
        assertThat(get("/api/v1/users/me", refreshed.jsonBody().get("accessToken").asString()).status()).isEqualTo(200);

        // The rotated token keeps working.
        assertThat(refresh(rotated).status()).isEqualTo(200);
    }

    @Test
    void reusingAnOldRefreshTokenRevokesTheWholeFamily() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String first = s.refreshCookie();
        Response r1 = refresh(first);
        String second = TestUsers.refreshCookieValue(r1);
        assertThat(r1.status()).isEqualTo(200);

        // An attacker replays the first (already rotated) token.
        Response replay = refresh(first);
        assertThat(replay.status()).isEqualTo(401);
        assertThat(replay.jsonBody().get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(TestUsers.setCookieHeader(replay)).contains("Max-Age=0");

        Integer reuseAudits = jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE action = 'REFRESH_TOKEN_REUSE' AND actor_user_id = ?",
                Integer.class, s.userId());
        assertThat(reuseAudits).isEqualTo(1);

        // ... which also kills the legitimate, newer token.
        assertThat(refresh(second).status()).isEqualTo(401);

        // A fresh login starts a new, working family.
        TestUsers.Session again = TestUsers.login(this, s.email(), TestUsers.PASSWORD);
        assertThat(refresh(again.refreshCookie()).status()).isEqualTo(200);
    }

    @Test
    void concurrentRefreshesWithTheSameTokenNeverBothSucceed() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> refresh(s.refreshCookie()).status());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                statuses.add(f.get());
            }
            assertThat(statuses.stream().filter(code -> code == 200).count()).isLessThanOrEqualTo(1);
            assertThat(statuses).allMatch(code -> code == 200 || code == 401);
        }
        // Reuse was detected, so no active token is left in the family.
        Integer active = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL", Integer.class, s.userId());
        assertThat(active).isZero();
    }

    @Test
    void refreshWithoutOrWithGarbageCookieIs401() {
        Response none = send(request("/api/v1/auth/refresh").POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(none.status()).isEqualTo(401);
        assertThat(none.jsonBody().get("code").asString()).isEqualTo("UNAUTHORIZED");
        assertThat(refresh("garbage").status()).isEqualTo(401);
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        Response out = send(request("/api/v1/auth/logout")
                .header("Cookie", "qg_refresh=" + s.refreshCookie())
                .POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(out.status()).isEqualTo(204);
        assertThat(TestUsers.setCookieHeader(out)).contains("Max-Age=0", "Path=/api/v1/auth");

        assertThat(refresh(s.refreshCookie()).status()).isEqualTo(401);

        // Logout without a cookie is still a harmless 204.
        Response again = send(request("/api/v1/auth/logout").POST(HttpRequest.BodyPublishers.noBody()).build());
        assertThat(again.status()).isEqualTo(204);
    }

    @Test
    void fiveFailedLoginsLockTheLogin() {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(this, email);
        Map<String, String> bad = Map.of("email", email, "password", "Wrong!Pass1");

        for (int i = 0; i < 4; i++) {
            assertThat(postJson("/api/v1/auth/login", bad, Map.of()).status()).isEqualTo(401);
        }
        Response fifth = postJson("/api/v1/auth/login", bad, Map.of());
        assertThat(fifth.status()).isEqualTo(429);
        assertThat(fifth.jsonBody().get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(Long.parseLong(fifth.header("Retry-After"))).isBetween(1L, 900L);

        // Even the correct password is refused while locked.
        Response correct = postJson("/api/v1/auth/login", Map.of("email", email, "password", TestUsers.PASSWORD), Map.of());
        assertThat(correct.status()).isEqualTo(429);

        // Once the lock has expired, the correct password works again.
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 second' WHERE email = ?", email);
        assertThat(TestUsers.login(this, email, TestUsers.PASSWORD).accessToken()).isNotBlank();
    }

    @Test
    void authEventsAreAudited() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE actor_user_id = ? ORDER BY created_at", String.class, s.userId());
        assertThat(actions).contains("USER_REGISTERED", "LOGIN_SUCCESS");
        String requestId = jdbc.queryForObject(
                "SELECT request_id FROM audit_logs WHERE actor_user_id = ? AND action = 'LOGIN_SUCCESS'",
                String.class, s.userId());
        assertThat(requestId).isNotBlank();
    }

    private Response refresh(String cookieValue) {
        return send(request("/api/v1/auth/refresh")
                .header("Cookie", "qg_refresh=" + cookieValue)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }
}
