package com.quantgen.support;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.quantgen.support.IntegrationTest.Response;

import tools.jackson.databind.JsonNode;

/** API helpers for money tests. */
public final class Bank {

    private Bank() {
    }

    public static Map<String, String> auth(TestUsers.Session s) {
        return Map.of("Authorization", "Bearer " + s.accessToken());
    }

    public static Map<String, String> authWithKey(TestUsers.Session s, UUID key) {
        Map<String, String> h = new HashMap<>(auth(s));
        h.put("Idempotency-Key", key.toString());
        return h;
    }

    /** The user's first (auto-created SAVINGS) account. */
    public static JsonNode firstAccount(IntegrationTest it, TestUsers.Session s) {
        Response res = it.get("/api/v1/accounts", s.accessToken());
        return res.jsonBody().get(0);
    }

    public static UUID firstAccountId(IntegrationTest it, TestUsers.Session s) {
        return UUID.fromString(firstAccount(it, s).get("id").asString());
    }

    public static String firstAccountNumber(IntegrationTest it, TestUsers.Session s) {
        return firstAccount(it, s).get("accountNumber").asString();
    }

    public static Response deposit(IntegrationTest it, TestUsers.Session s, UUID accountId, String amount) {
        return deposit(it, s, accountId, amount, UUID.randomUUID());
    }

    public static Response deposit(IntegrationTest it, TestUsers.Session s, UUID accountId, String amount, UUID key) {
        return it.postJson("/api/v1/accounts/" + accountId + "/deposit", Map.of("amount", amount), authWithKey(s, key));
    }

    public static Response transfer(IntegrationTest it, TestUsers.Session s, UUID from, String toNumber, String amount,
                                    UUID key) {
        return it.postJson("/api/v1/transfers",
                Map.of("fromAccountId", from.toString(), "toAccountNumber", toNumber, "amount", amount),
                authWithKey(s, key));
    }

    public static String balance(IntegrationTest it, TestUsers.Session s, UUID accountId) {
        return it.get("/api/v1/accounts/" + accountId, s.accessToken()).jsonBody().get("balance").asString();
    }

    public static final String PIN = "246810";

    /** Sets the transaction PIN (first PIN: proven with the account password) unless the user already has one. */
    public static void ensurePin(IntegrationTest it, TestUsers.Session s) {
        if (!it.get("/api/v1/users/me", s.accessToken()).jsonBody().get("hasPin").asBoolean()) {
            Response res = it.put("/api/v1/users/me/pin", Map.of("pin", PIN, "password", TestUsers.PASSWORD),
                    s.accessToken());
            if (res.status() != 204) {
                throw new IllegalStateException("could not set PIN: " + res.status() + " " + res.body());
            }
        }
    }

    /** A step-up token for {@code action}, setting the PIN first if needed. */
    public static String stepUpToken(IntegrationTest it, TestUsers.Session s, String action) {
        ensurePin(it, s);
        Response res = it.postJson("/api/v1/auth/step-up/verify", Map.of("action", action, "pin", PIN), auth(s));
        if (res.status() != 200) {
            throw new IllegalStateException("step-up failed: " + res.status() + " " + res.body());
        }
        return res.jsonBody().get("stepUpToken").asString();
    }

    /** A transfer that carries a step-up token, for amounts above the step-up threshold. */
    public static Response transferStepUp(IntegrationTest it, TestUsers.Session s, UUID from, String toNumber,
                                          String amount, UUID key) {
        Map<String, String> headers = new HashMap<>(authWithKey(s, key));
        headers.put("X-Step-Up-Token", stepUpToken(it, s, "TRANSFER"));
        return it.postJson("/api/v1/transfers",
                Map.of("fromAccountId", from.toString(), "toAccountNumber", toNumber, "amount", amount), headers);
    }

    /** A fresh ADMIN session (registered as a customer, then promoted directly in the DB). */
    public static TestUsers.Session admin(IntegrationTest it) {
        String email = TestUsers.uniqueEmail();
        TestUsers.register(it, email);
        it.jdbc.update("UPDATE users SET role = 'ADMIN' WHERE email = ?", email);
        return TestUsers.login(it, email, TestUsers.PASSWORD);
    }
}
