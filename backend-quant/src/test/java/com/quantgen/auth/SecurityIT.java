package com.quantgen.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M1 Security 2.0: PIN, step-up, limits, new-payee cooling, sessions, KYC. */
class SecurityIT extends IntegrationTest {

    private static final String PAN = "ABCDE1234F";

    // ------------------------------------------------------------------ PIN

    @Test
    void firstPinNeedsThePasswordAndChangingItNeedsStepUp() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        Response noPassword = put("/api/v1/users/me/pin", Map.of("pin", "246810"), s.accessToken());
        assertThat(noPassword.status()).isEqualTo(400);
        assertThat(noPassword.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("password");

        Response wrongPassword = put("/api/v1/users/me/pin", Map.of("pin", "246810", "password", "Wr0ng!Pass"),
                s.accessToken());
        assertThat(wrongPassword.status()).isEqualTo(400);

        assertThat(put("/api/v1/users/me/pin", Map.of("pin", "246810", "password", TestUsers.PASSWORD), s.accessToken())
                .status()).isEqualTo(204);
        JsonNode me = get("/api/v1/users/me", s.accessToken()).jsonBody();
        assertThat(me.get("hasPin").asBoolean()).isTrue();
        assertThat(me.get("hasPasskey").asBoolean()).isFalse();
        assertThat(me.get("kycStatus").asString()).isEqualTo("NONE");

        // With a PIN on file the password no longer works as proof: a step-up is required.
        Response change = put("/api/v1/users/me/pin", Map.of("pin", "135790", "password", TestUsers.PASSWORD),
                s.accessToken());
        assertThat(change.status()).isEqualTo(401);
        assertThat(change.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        String token = Bank.stepUpToken(this, s, "SECURITY_SETTINGS");
        assertThat(putWithToken("/api/v1/users/me/pin", Map.of("pin", "135790"), s, token).status()).isEqualTo(204);
        assertThat(stepUpVerify(s, "TRANSFER", "246810").status()).isEqualTo(400); // old PIN is dead
        assertThat(stepUpVerify(s, "TRANSFER", "135790").status()).isEqualTo(200);
    }

    @Test
    void weakAndMalformedPinsAreRejected() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        for (String pin : new String[]{"111111", "123456", "12345", "1234567", "abcdef", "12 456"}) {
            Response res = put("/api/v1/users/me/pin", Map.of("pin", pin, "password", TestUsers.PASSWORD), s.accessToken());
            assertThat(res.status()).as(pin).isEqualTo(400);
        }
    }

    @Test
    void wrongPinsLockTheStepUpAfterFiveTriesAndCorrectOnesStillFailWhileLocked() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Bank.ensurePin(this, s);

        for (int i = 1; i <= 4; i++) {
            Response bad = stepUpVerify(s, "TRANSFER", "000001");
            assertThat(bad.status()).isEqualTo(400); // never 401: the client would treat that as a dead session
            assertThat(bad.jsonBody().get("fieldErrors").get(0).get("message").asString())
                    .contains((5 - i) + " attempts left");
        }
        Response locked = stepUpVerify(s, "TRANSFER", "000001");
        assertThat(locked.status()).isEqualTo(423);
        assertThat(locked.jsonBody().get("code").asString()).isEqualTo("PIN_LOCKED");
        assertThat(locked.raw().headers().firstValue("Retry-After")).isPresent();

        Response correctButLocked = stepUpVerify(s, "TRANSFER", Bank.PIN);
        assertThat(correctButLocked.status()).isEqualTo(423);

        jdbc.update("UPDATE user_pins SET locked_until = now() - interval '1 second' WHERE user_id = ?", s.userId());
        assertThat(stepUpVerify(s, "TRANSFER", Bank.PIN).status()).isEqualTo(200);
    }

    @Test
    void stepUpEndpointsRequireSignIn() {
        assertThat(postJson("/api/v1/auth/step-up/options", Map.of("action", "TRANSFER"), Map.of()).status())
                .isEqualTo(401);
        assertThat(postJson("/api/v1/auth/step-up/verify", Map.of("action", "TRANSFER", "pin", "246810"), Map.of())
                .status()).isEqualTo(401);
    }

    // ------------------------------------------------------------------ step-up on transfers

    @Test
    void transferAbove50kNeedsAStepUpAndSmallOnesDoNot() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        String bobNumber = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, acc, "100000.00");
        Bank.deposit(this, alice, acc, "100000.00");

        assertThat(Bank.transfer(this, alice, acc, bobNumber, "50000.00", UUID.randomUUID()).status()).isEqualTo(201);

        // No PIN yet: the 401 says there is no method, so the client sends the user to set one up.
        UUID key = UUID.randomUUID();
        Response needs = Bank.transfer(this, alice, acc, bobNumber, "50000.01", key);
        assertThat(needs.status()).isEqualTo(401);
        JsonNode body = needs.jsonBody();
        assertThat(body.get("code").asString()).isEqualTo("STEP_UP_REQUIRED");
        assertThat(body.get("stepUp").get("action").asString()).isEqualTo("TRANSFER");
        assertThat(body.get("stepUp").get("methods").size()).isZero();

        Bank.ensurePin(this, alice);
        Response withPin = Bank.transfer(this, alice, acc, bobNumber, "50000.01", key);
        assertThat(withPin.jsonBody().get("stepUp").get("methods").get(0).asString()).isEqualTo("PIN");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("150000.00"); // nothing moved

        // The rejected attempt did not consume the key: the retry with a token and the SAME key works once.
        String token = Bank.stepUpToken(this, alice, "TRANSFER");
        Map<String, String> headers = new HashMap<>(Bank.authWithKey(alice, key));
        headers.put("X-Step-Up-Token", token);
        Map<String, Object> req = Map.of("fromAccountId", acc.toString(), "toAccountNumber", bobNumber,
                "amount", "50000.01");
        assertThat(postJson("/api/v1/transfers", req, headers).status()).isEqualTo(201);
        // A replay of the finished request returns the stored result even without a token (no second debit).
        Response replay = postJson("/api/v1/transfers", req, Bank.authWithKey(alice, key));
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.raw().headers().firstValue("Idempotent-Replayed")).isPresent();
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("99999.99");
    }

    @Test
    void aStepUpTokenIsBoundToItsUserAndActionAndExpires() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session mallory = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        String bobNumber = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, acc, "100000.00");
        Bank.ensurePin(this, alice);

        String wrongAction = Bank.stepUpToken(this, alice, "CARD_REVEAL");
        assertThat(transferWithToken(alice, acc, bobNumber, wrongAction).status()).isEqualTo(401);

        String stolen = Bank.stepUpToken(this, alice, "TRANSFER");
        Bank.deposit(this, mallory, Bank.firstAccountId(this, mallory), "100000.00");
        assertThat(transferWithToken(mallory, Bank.firstAccountId(this, mallory), bobNumber, stolen).status())
                .isEqualTo(401);

        assertThat(transferWithToken(alice, acc, bobNumber, "not-a-token").status()).isEqualTo(401);

        jdbc.update("UPDATE step_up_tokens SET expires_at = now() - interval '1 second' WHERE user_id = ?", alice.userId());
        assertThat(transferWithToken(alice, acc, bobNumber, stolen).status()).isEqualTo(401);
        // The token is stored hashed.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM step_up_tokens WHERE token_hash = ?", Integer.class, stolen))
                .isZero();
    }

    // ------------------------------------------------------------------ limits

    @Test
    void customersCanLowerTheirLimitsWithStepUpAndTheyAreEnforced() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        String bobNumber = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, acc, "100000.00");

        JsonNode defaults = get("/api/v1/users/me/limits", alice.accessToken()).jsonBody();
        assertThat(defaults.get("perTransfer").asString()).isEqualTo("200000.00");
        assertThat(defaults.get("daily").asString()).isEqualTo("500000.00");
        assertThat(defaults.get("usedToday").asString()).isEqualTo("0.00");

        assertThat(put("/api/v1/users/me/limits", Map.of("perTransfer", "1000.00"), alice.accessToken()).status())
                .isEqualTo(401); // no step-up

        String token = Bank.stepUpToken(this, alice, "SECURITY_SETTINGS");
        Response set = putWithToken("/api/v1/users/me/limits", Map.of("perTransfer", "1000.00", "daily", "1500.00"),
                alice, token);
        assertThat(set.status()).isEqualTo(200);
        assertThat(set.jsonBody().get("perTransfer").asString()).isEqualTo("1000.00");

        Response over = Bank.transfer(this, alice, acc, bobNumber, "1000.01", UUID.randomUUID());
        assertThat(over.status()).isEqualTo(422);
        assertThat(over.jsonBody().get("code").asString()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(Bank.transfer(this, alice, acc, bobNumber, "1000.00", UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(Bank.transfer(this, alice, acc, bobNumber, "600.00", UUID.randomUUID()).status()).isEqualTo(422); // daily 1500
        assertThat(get("/api/v1/users/me/limits", alice.accessToken()).jsonBody().get("usedToday").asString())
                .isEqualTo("1000.00");

        // Cannot exceed the platform maximum, nor make the per-transfer limit larger than the daily one.
        assertThat(putWithToken("/api/v1/users/me/limits", Map.of("daily", "500000.01"), alice, token).status())
                .isEqualTo(400);
        assertThat(putWithToken("/api/v1/users/me/limits", Map.of("perTransfer", "2000.00"), alice, token).status())
                .isEqualTo(400);
        assertThat(putWithToken("/api/v1/users/me/limits", Map.of(), alice, token).status()).isEqualTo(400);
    }

    // ------------------------------------------------------------------ new-payee cooling

    @Test
    void aNewPayeeIsCappedForTheFirstDayAndNeedsStepUpForTheFirstTransfer() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        String bobNumber = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, acc, "100000.00");
        assertThat(postJson("/api/v1/beneficiaries", Map.of("nickname", "Bob", "accountNumber", bobNumber),
                Bank.auth(alice)).status()).isEqualTo(201);

        // First transfer to a payee added < 24 h ago needs a step-up, even for a small amount.
        Response first = Bank.transfer(this, alice, acc, bobNumber, "100.00", UUID.randomUUID());
        assertThat(first.status()).isEqualTo(401);
        assertThat(first.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        assertThat(Bank.transferStepUp(this, alice, acc, bobNumber, "20000.00", UUID.randomUUID()).status())
                .isEqualTo(201);
        // After that first one, small transfers need no step-up, but the 24 h total is capped at 25,000.
        assertThat(Bank.transfer(this, alice, acc, bobNumber, "5000.00", UUID.randomUUID()).status()).isEqualTo(201);
        Response capped = Bank.transfer(this, alice, acc, bobNumber, "1.00", UUID.randomUUID());
        assertThat(capped.status()).isEqualTo(422);
        assertThat(capped.jsonBody().get("code").asString()).isEqualTo("COOLING_PERIOD");

        // Once the payee is a day old the cap lifts.
        jdbc.update("UPDATE beneficiaries SET created_at = now() - interval '25 hours' WHERE user_id = ?", alice.userId());
        assertThat(Bank.transfer(this, alice, acc, bobNumber, "1000.00", UUID.randomUUID()).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ sessions

    @Test
    void sessionsAreListedMarkedCurrentAndRevocable() {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(this, email);
        Response laptop = loginAs(email, "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/120.0 Safari/537.36");
        Response phone = loginAs(email, "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) Safari/604.1");
        String laptopToken = laptop.jsonBody().get("accessToken").asString();
        String phoneToken = phone.jsonBody().get("accessToken").asString();

        JsonNode list = get("/api/v1/users/me/sessions", laptopToken).jsonBody();
        assertThat(list.size()).isEqualTo(2);
        JsonNode current = null;
        JsonNode other = null;
        for (JsonNode n : list) {
            if (n.get("current").asBoolean()) {
                current = n;
            } else {
                other = n;
            }
        }
        assertThat(current).isNotNull();
        assertThat(current.get("device").asString()).isEqualTo("Windows");
        assertThat(current.get("browser").asString()).isEqualTo("Chrome");
        assertThat(other.get("device").asString()).isEqualTo("iOS");

        // The second device triggered a notification to the owner; the first sign-in did not.
        JsonNode notes = get("/api/v1/notifications", laptopToken).jsonBody();
        long newDevice = 0;
        for (JsonNode n : notes) {
            newDevice += "NEW_DEVICE_LOGIN".equals(n.get("type").asString()) ? 1 : 0;
        }
        assertThat(newDevice).isEqualTo(1);

        // Revoke the phone: its refresh token stops working, the laptop's still works.
        String phoneCookie = TestUsers.refreshCookieValue(phone);
        String laptopCookie = TestUsers.refreshCookieValue(laptop);
        assertThat(delete("/api/v1/users/me/sessions/" + other.get("id").asString(), laptopToken).status())
                .isEqualTo(204);
        assertThat(refresh(phoneCookie).status()).isEqualTo(401);
        assertThat(refresh(laptopCookie).status()).isEqualTo(200);
        assertThat(get("/api/v1/users/me/sessions", laptopToken).jsonBody().size()).isEqualTo(1);
        assertThat(phoneToken).isNotBlank();
    }

    @Test
    void revokeOthersKeepsTheCurrentSessionAndSessionsAreOwnerScoped() {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(this, email);
        Response a = loginAs(email, "Mozilla/5.0 (X11; Linux x86_64) Firefox/121.0");
        Response b = loginAs(email, "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Safari/605.1.15");
        Response c = loginAs(email, "Mozilla/5.0 (Linux; Android 14) Chrome/120.0 Mobile Safari/537.36");
        String bToken = b.jsonBody().get("accessToken").asString();

        assertThat(delete("/api/v1/users/me/sessions?others=true", bToken).status()).isEqualTo(204);
        assertThat(get("/api/v1/users/me/sessions", bToken).jsonBody().size()).isEqualTo(1);
        assertThat(refresh(TestUsers.refreshCookieValue(a)).status()).isEqualTo(401);
        assertThat(refresh(TestUsers.refreshCookieValue(c)).status()).isEqualTo(401);
        assertThat(refresh(TestUsers.refreshCookieValue(b)).status()).isEqualTo(200);
        assertThat(delete("/api/v1/users/me/sessions", bToken).status()).isEqualTo(400); // others=true is required

        // Another user's session id is a 404, same as an unknown one.
        TestUsers.Session stranger = TestUsers.registerAndLogin(this);
        UUID foreign = jdbc.queryForObject("SELECT family_id FROM refresh_tokens WHERE user_id = ? LIMIT 1", UUID.class,
                stranger.userId());
        assertThat(delete("/api/v1/users/me/sessions/" + foreign, bToken).status()).isEqualTo(404);
        assertThat(delete("/api/v1/users/me/sessions/" + UUID.randomUUID(), bToken).status()).isEqualTo(404);
        assertThat(get("/api/v1/users/me/sessions", stranger.accessToken()).jsonBody().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ KYC

    @Test
    void kycIsValidatedEncryptedMaskedAndUnique() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        String pan = "QWERT" + (1000 + Math.abs(UUID.randomUUID().hashCode() % 9000)) + "Z";

        assertThat(get("/api/v1/users/me/kyc", s.accessToken()).jsonBody().get("status").asString()).isEqualTo("NONE");

        assertThat(putKyc(s, Map.of("pan", "nope", "dateOfBirth", "1990-01-01", "address", "12 Long Street, Pune"), null)
                .status()).isEqualTo(400);
        assertThat(putKyc(s, Map.of("pan", pan, "dateOfBirth", "2015-01-01", "address", "12 Long Street, Pune"), null)
                .status()).isEqualTo(400); // under 18
        assertThat(putKyc(s, Map.of("pan", pan, "dateOfBirth", "1990-01-01", "address", "short"), null).status())
                .isEqualTo(400);

        Response ok = putKyc(s, Map.of("pan", pan.toLowerCase(), "dateOfBirth", "1990-01-01",
                "address", "12 Long Street, Pune"), null);
        assertThat(ok.status()).isEqualTo(200);
        JsonNode kyc = ok.jsonBody();
        assertThat(kyc.get("status").asString()).isEqualTo("VERIFIED");
        assertThat(kyc.get("panMasked").asString()).isEqualTo("XXXXXX" + pan.substring(6));
        assertThat(ok.body()).doesNotContain(pan);
        assertThat(get("/api/v1/users/me", s.accessToken()).jsonBody().get("kycStatus").asString()).isEqualTo("VERIFIED");

        // At rest: ciphertext and blind index only, never the PAN or the address.
        Map<String, Object> row = jdbc.queryForMap("SELECT pan_enc, pan_idx, address_enc FROM kyc_profiles "
                + "WHERE user_id = ?", s.userId());
        assertThat(row.get("pan_enc").toString()).startsWith("v1.").doesNotContain(pan);
        assertThat(row.get("address_enc").toString()).doesNotContain("Long Street");
        assertThat(row.get("pan_idx").toString()).hasSize(64).doesNotContain(pan);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE details LIKE ?", Integer.class,
                "%" + pan + "%")).isZero();

        // The same PAN cannot be linked to a second account.
        Response dup = putKyc(other, Map.of("pan", pan, "dateOfBirth", "1991-02-02", "address", "99 Other Road, Delhi"), null);
        assertThat(dup.status()).isEqualTo(409);

        // Replacing details already on file needs a step-up.
        Map<String, Object> change = Map.of("pan", "ZZZZZ9999Z", "dateOfBirth", "1990-01-01",
                "address", "12 Long Street, Pune");
        assertThat(putKyc(s, change, null).status()).isEqualTo(401);
        assertThat(putKyc(s, change, Bank.stepUpToken(this, s, "SECURITY_SETTINGS")).status()).isEqualTo(200);
    }

    @Test
    void loansAbove200kNeedKyc() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Map<String, Object> apply = Map.of("amount", "300000.00", "tenureMonths", 24, "monthlyIncome", "200000.00",
                "purpose", "Home improvement", "disburseAccountId", acc.toString());

        Response blocked = postJson("/api/v1/loans", apply, Bank.auth(s));
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.jsonBody().get("code").asString()).isEqualTo("KYC_REQUIRED");

        assertThat(putKyc(s, Map.of("pan", "LOANX1234Q", "dateOfBirth", "1988-05-05",
                "address", "5 Finance Lane, Mumbai"), null).status()).isEqualTo(200);
        assertThat(postJson("/api/v1/loans", apply, Bank.auth(s)).status()).isEqualTo(201);
    }

    // ------------------------------------------------------------------ helpers

    private Response stepUpVerify(TestUsers.Session s, String action, String pin) {
        return postJson("/api/v1/auth/step-up/verify", Map.of("action", action, "pin", pin), Bank.auth(s));
    }

    private Response putWithToken(String path, Object body, TestUsers.Session s, String token) {
        return send(request(path).header("Authorization", "Bearer " + s.accessToken())
                .header("Content-Type", "application/json").header("X-Step-Up-Token", token)
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
    }

    private Response putKyc(TestUsers.Session s, Map<String, Object> body, String token) {
        return token == null ? put("/api/v1/users/me/kyc", body, s.accessToken())
                : putWithToken("/api/v1/users/me/kyc", body, s, token);
    }

    private Response transferWithToken(TestUsers.Session s, UUID from, String toNumber, String token) {
        Map<String, String> headers = new HashMap<>(Bank.authWithKey(s, UUID.randomUUID()));
        headers.put("X-Step-Up-Token", token);
        return postJson("/api/v1/transfers",
                Map.of("fromAccountId", from.toString(), "toAccountNumber", toNumber, "amount", "60000.00"), headers);
    }

    private Response loginAs(String email, String userAgent) {
        return postJson("/api/v1/auth/login", Map.of("email", email, "password", TestUsers.PASSWORD),
                Map.of("User-Agent", userAgent));
    }

    private Response refresh(String cookieValue) {
        return send(request("/api/v1/auth/refresh").header("Cookie", "qg_refresh=" + cookieValue)
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }
}
