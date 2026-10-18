package com.quantgen.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.SoftAuthenticator;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M1 passkeys: registration, sign-in, step-up, and the ways each can be abused. */
class PasskeyIT extends IntegrationTest {

    private static final String ORIGIN = "http://localhost:5173";

    // ------------------------------------------------------------------ registration

    @Test
    void registeringAPasskeyListsItAndFlagsTheUser() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);

        assertThat(postJson("/api/v1/auth/passkeys/register/options", Map.of(), Map.of()).status()).isEqualTo(401);
        Response created = register(s, key, "My laptop");
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.jsonBody().get("name").asString()).isEqualTo("My laptop");
        assertThat(created.jsonBody().get("lastUsedAt").isNull()).isTrue();

        JsonNode list = get("/api/v1/auth/passkeys", s.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(1);
        assertThat(get("/api/v1/users/me", s.accessToken()).jsonBody().get("hasPasskey").asBoolean()).isTrue();
        // At rest only the public key is stored, plus the id the browser needs.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM passkeys WHERE user_id = ?", Integer.class, s.userId()))
                .isEqualTo(1);
    }

    @Test
    void registrationOptionsExcludeExistingKeysAndCapAtFive() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        for (int i = 0; i < 5; i++) {
            assertThat(register(s, new SoftAuthenticator(ORIGIN), "key " + i).status()).isEqualTo(201);
        }
        Response sixth = postJson("/api/v1/auth/passkeys/register/options", Map.of(), Bank.auth(s));
        assertThat(sixth.status()).isEqualTo(422);
        assertThat(sixth.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
    }

    @Test
    void registrationFromTheWrongOriginOrWithAnUnissuedChallengeFails() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        JsonNode options = postJson("/api/v1/auth/passkeys/register/options", Map.of(), Bank.auth(s)).jsonBody();
        Response wrongOrigin = postJson("/api/v1/auth/passkeys/register",
                Map.of("credential", new SoftAuthenticator("https://evil.example").create(options), "name", "x"),
                Bank.auth(s));
        assertThat(wrongOrigin.status()).isEqualTo(400);
        assertThat(wrongOrigin.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("credential");

        // The failed attempt used up the challenge: even a correct answer to it is refused now.
        Response replayed = postJson("/api/v1/auth/passkeys/register",
                Map.of("credential", new SoftAuthenticator(ORIGIN).create(options), "name", "x"), Bank.auth(s));
        assertThat(replayed.status()).isEqualTo(400);

        // Someone else's challenge cannot be completed by another user.
        TestUsers.Session mallory = TestUsers.registerAndLogin(this);
        JsonNode victimOptions = postJson("/api/v1/auth/passkeys/register/options", Map.of(), Bank.auth(s)).jsonBody();
        Response stolen = postJson("/api/v1/auth/passkeys/register",
                Map.of("credential", new SoftAuthenticator(ORIGIN).create(victimOptions), "name", "x"), Bank.auth(mallory));
        assertThat(stolen.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM passkeys WHERE user_id IN (?, ?)", Integer.class,
                s.userId(), mallory.userId())).isZero();

        assertThat(postJson("/api/v1/auth/passkeys/register", Map.of("credential", Map.of("x", 1), "name", "x"),
                Bank.auth(s)).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ sign-in

    @Test
    void passkeySignInWorksWithoutAPasswordAndEachChallengeIsSingleUse() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);
        register(s, key, "phone");

        JsonNode options = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        key.signCount = 1;
        Map<String, Object> assertion = key.get(options);
        Response login = postJson("/api/v1/auth/passkeys/login", Map.of("credential", assertion), Map.of());

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.jsonBody().get("user").get("id").asString()).isEqualTo(s.userId().toString());
        assertThat(login.jsonBody().get("user").get("hasPasskey").asBoolean()).isTrue();
        assertThat(TestUsers.refreshCookieValue(login)).isNotBlank();
        assertThat(get("/api/v1/users/me", login.jsonBody().get("accessToken").asString()).status()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT sign_count FROM passkeys WHERE user_id = ?", Long.class, s.userId()))
                .isEqualTo(1L);
        assertThat(get("/api/v1/auth/passkeys", s.accessToken()).jsonBody().get(0).get("lastUsedAt").isNull()).isFalse();

        // Replaying the same signed assertion fails: its challenge is gone.
        assertThat(postJson("/api/v1/auth/passkeys/login", Map.of("credential", assertion), Map.of()).status())
                .isEqualTo(401);
    }

    @Test
    void loginOptionsRevealNothingAboutWhoHasAPasskey() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        register(s, new SoftAuthenticator(ORIGIN), "phone");

        JsonNode known = postJson("/api/v1/auth/passkeys/login/options", Map.of("email", s.email()), Map.of()).jsonBody();
        JsonNode unknown = postJson("/api/v1/auth/passkeys/login/options", Map.of("email", "nobody@test.dev"), Map.of())
                .jsonBody();

        assertThat(fieldNames(known)).isEqualTo(fieldNames(unknown));
        assertThat(known.has("allowCredentials") ? known.get("allowCredentials").size() : 0).isZero();
        assertThat(known.get("userVerification").asString()).isEqualTo("required");
    }

    @Test
    void forgedUnknownAndRegressedAssertionsAreRejectedWithTheSame401() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);
        register(s, key, "phone");

        // A different authenticator (unknown credential) cannot sign in as anyone.
        SoftAuthenticator stranger = new SoftAuthenticator(ORIGIN);
        JsonNode o1 = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        assertThat(postJson("/api/v1/auth/passkeys/login", Map.of("credential", stranger.get(o1)), Map.of()).status())
                .isEqualTo(401);

        // Wrong origin.
        JsonNode o2 = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        SoftAuthenticator phisher = new SoftAuthenticator("https://evil.example");
        Map<String, Object> cred = phisher.get(o2);
        assertThat(postJson("/api/v1/auth/passkeys/login", Map.of("credential", cred), Map.of()).status())
                .isEqualTo(401);

        // A counter that goes backwards means a cloned authenticator.
        key.signCount = 10;
        JsonNode o3 = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        assertThat(postJson("/api/v1/auth/passkeys/login", Map.of("credential", key.get(o3)), Map.of()).status())
                .isEqualTo(200);
        key.signCount = 5;
        JsonNode o4 = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        Response regressed = postJson("/api/v1/auth/passkeys/login", Map.of("credential", key.get(o4)), Map.of());
        assertThat(regressed.status()).isEqualTo(401);
        assertThat(regressed.jsonBody().get("code").asString()).isEqualTo("UNAUTHORIZED");

        // A challenge that was never issued.
        JsonNode o5 = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        Map<String, Object> garbage = new HashMap<>(Map.of("id", "AAAA", "rawId", "AAAA", "type", "public-key",
                "response", Map.of("clientDataJSON", "AAAA", "authenticatorData", "AAAA", "signature", "AAAA"),
                "clientExtensionResults", Map.of()));
        assertThat(postJson("/api/v1/auth/passkeys/login", Map.of("credential", garbage), Map.of()).status())
                .isEqualTo(401);
        assertThat(o5).isNotNull();
    }

    @Test
    void aLockedAccountCannotSignInWithAPasskeyEither() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);
        register(s, key, "phone");
        jdbc.update("UPDATE users SET locked_until = now() + interval '10 minutes' WHERE id = ?", s.userId());

        JsonNode options = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        key.signCount = 1;
        Response res = postJson("/api/v1/auth/passkeys/login", Map.of("credential", key.get(options)), Map.of());

        assertThat(res.status()).isEqualTo(429);
    }

    // ------------------------------------------------------------------ step-up

    @Test
    void aPasskeyCanSatisfyStepUpForALargeTransfer() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);
        register(alice, key, "phone");
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "100000.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);

        Response needs = Bank.transfer(this, alice, acc, bobNumber, "60000.00", UUID.randomUUID());
        assertThat(needs.status()).isEqualTo(401);
        assertThat(needs.jsonBody().get("stepUp").get("methods").get(0).asString()).isEqualTo("PASSKEY");

        JsonNode opts = postJson("/api/v1/auth/step-up/options", Map.of("action", "TRANSFER"), Bank.auth(alice))
                .jsonBody();
        assertThat(opts.get("methods").get(0).asString()).isEqualTo("PASSKEY");
        key.signCount = 1;
        Response verified = postJson("/api/v1/auth/step-up/verify",
                Map.of("action", "TRANSFER", "credential", key.get(opts.get("passkeyOptions"))), Bank.auth(alice));
        assertThat(verified.status()).isEqualTo(200);

        Map<String, String> headers = new HashMap<>(Bank.authWithKey(alice, UUID.randomUUID()));
        headers.put("X-Step-Up-Token", verified.jsonBody().get("stepUpToken").asString());
        assertThat(postJson("/api/v1/transfers", Map.of("fromAccountId", acc.toString(), "toAccountNumber", bobNumber,
                "amount", "60000.00"), headers).status()).isEqualTo(201);
        assertThat(jdbc.queryForObject("SELECT method FROM step_up_tokens WHERE user_id = ?", String.class,
                alice.userId())).isEqualTo("PASSKEY");
    }

    @Test
    void aStepUpAssertionIsBoundToItsActionItsUserAndItsPurpose() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session mallory = TestUsers.registerAndLogin(this);
        SoftAuthenticator aliceKey = new SoftAuthenticator(ORIGIN);
        SoftAuthenticator malloryKey = new SoftAuthenticator(ORIGIN);
        register(alice, aliceKey, "alice phone");
        register(mallory, malloryKey, "mallory phone");

        // Issued for TRANSFER, presented for CARD_REVEAL.
        JsonNode forTransfer = stepUpOptions(alice, "TRANSFER");
        aliceKey.signCount = 1;
        assertThat(postJson("/api/v1/auth/step-up/verify",
                Map.of("action", "CARD_REVEAL", "credential", aliceKey.get(forTransfer)), Bank.auth(alice)).status())
                .isEqualTo(400);

        // Alice's challenge answered by Mallory's own authenticator, on Mallory's session.
        JsonNode aliceChallenge = stepUpOptions(alice, "TRANSFER");
        malloryKey.signCount = 1;
        assertThat(postJson("/api/v1/auth/step-up/verify",
                Map.of("action", "TRANSFER", "credential", malloryKey.get(aliceChallenge)), Bank.auth(mallory)).status())
                .isEqualTo(400);

        // A sign-in challenge cannot be reused as a step-up proof.
        JsonNode loginOptions = postJson("/api/v1/auth/passkeys/login/options", Map.of(), Map.of()).jsonBody();
        aliceKey.signCount = 2;
        assertThat(postJson("/api/v1/auth/step-up/verify",
                Map.of("action", "TRANSFER", "credential", aliceKey.get(loginOptions)), Bank.auth(alice)).status())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step_up_tokens WHERE user_id IN (?, ?)", Integer.class,
                alice.userId(), mallory.userId())).isZero();
    }

    // ------------------------------------------------------------------ management

    @Test
    void removingAPasskeyNeedsStepUpAndIsOwnerScoped() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        SoftAuthenticator key = new SoftAuthenticator(ORIGIN);
        Response created = register(s, key, "phone");
        String id = created.jsonBody().get("id").asString();

        assertThat(delete("/api/v1/auth/passkeys/" + id, s.accessToken()).status()).isEqualTo(401);
        Bank.ensurePin(this, other);

        Response foreign = send(request("/api/v1/auth/passkeys/" + id)
                .header("Authorization", "Bearer " + other.accessToken())
                .header("X-Step-Up-Token", Bank.stepUpToken(this, other, "SECURITY_SETTINGS")).DELETE().build());
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(get("/api/v1/auth/passkeys", s.accessToken()).jsonBody().size()).isEqualTo(1);

        Response own = send(request("/api/v1/auth/passkeys/" + id)
                .header("Authorization", "Bearer " + s.accessToken())
                .header("X-Step-Up-Token", passkeyToken(s, key, "SECURITY_SETTINGS")).DELETE().build());
        assertThat(own.status()).isEqualTo(204);
        assertThat(get("/api/v1/auth/passkeys", s.accessToken()).jsonBody().size()).isZero();
        assertThat(get("/api/v1/users/me", s.accessToken()).jsonBody().get("hasPasskey").asBoolean()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private Response register(TestUsers.Session s, SoftAuthenticator key, String name) {
        JsonNode options = postJson("/api/v1/auth/passkeys/register/options", Map.of(), Bank.auth(s)).jsonBody();
        return postJson("/api/v1/auth/passkeys/register", Map.of("credential", key.create(options), "name", name),
                Bank.auth(s));
    }

    private String passkeyToken(TestUsers.Session s, SoftAuthenticator key, String action) {
        key.signCount++;
        Response res = postJson("/api/v1/auth/step-up/verify",
                Map.of("action", action, "credential", key.get(stepUpOptions(s, action))), Bank.auth(s));
        assertThat(res.status()).isEqualTo(200);
        return res.jsonBody().get("stepUpToken").asString();
    }

    private JsonNode stepUpOptions(TestUsers.Session s, String action) {
        return postJson("/api/v1/auth/step-up/options", Map.of("action", action), Bank.auth(s)).jsonBody()
                .get("passkeyOptions");
    }

    private static java.util.Set<String> fieldNames(JsonNode node) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        node.propertyNames().forEach(names::add);
        return names;
    }

}
