package com.quantgen.support;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.quantgen.support.IntegrationTest.Response;

/** Registers and logs in throwaway users through the real API. */
public final class TestUsers {

    public static final String PASSWORD = "Str0ng!Pass";

    private TestUsers() {
    }

    public record Session(UUID userId, String email, String accessToken, String refreshCookie) {
    }

    public static String uniqueEmail() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "@test.dev";
    }

    public static Response register(IntegrationTest it, String email) {
        return it.postJson("/api/v1/auth/register", Map.of(
                "fullName", "Test User",
                "email", email,
                "phone", "+919876543210",
                "password", PASSWORD), Map.of());
    }

    public static Session registerAndLogin(IntegrationTest it) {
        String email = uniqueEmail();
        Response reg = register(it, email);
        if (reg.status() != 201) {
            throw new IllegalStateException("register failed: " + reg.body());
        }
        return login(it, email, PASSWORD);
    }

    public static Session login(IntegrationTest it, String email, String password) {
        Response res = it.postJson("/api/v1/auth/login", Map.of("email", email, "password", password), Map.of());
        if (res.status() != 200) {
            throw new IllegalStateException("login failed: " + res.status() + " " + res.body());
        }
        return new Session(UUID.fromString(res.jsonBody().get("user").get("id").asString()), email,
                res.jsonBody().get("accessToken").asString(), refreshCookieValue(res));
    }

    /** The raw qg_refresh value from Set-Cookie, or null if absent. */
    public static String refreshCookieValue(Response res) {
        List<String> cookies = res.raw().headers().allValues("Set-Cookie");
        for (String c : cookies) {
            if (c.startsWith("qg_refresh=")) {
                return c.substring("qg_refresh=".length(), c.indexOf(';'));
            }
        }
        return null;
    }

    public static String setCookieHeader(Response res) {
        return res.raw().headers().allValues("Set-Cookie").stream()
                .filter(c -> c.startsWith("qg_refresh="))
                .findFirst().orElse(null);
    }
}
