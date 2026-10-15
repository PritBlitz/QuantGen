package com.quantgen.auth.api;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.quantgen.auth.service.AuthProperties;

/** The {@code qg_refresh} cookie (F-AUTH-2): HttpOnly, Secure, SameSite=Strict, path /api/v1/auth. */
@Component
class RefreshCookies {

    static final String NAME = "qg_refresh";
    static final String PATH = "/api/v1/auth";

    private final boolean secure;

    RefreshCookies(AuthProperties props) {
        this.secure = props.auth().cookieSecure();
    }

    String issue(String token, Duration ttl) {
        return base(token).maxAge(ttl).build().toString();
    }

    String clear() {
        return base("").maxAge(Duration.ZERO).build().toString();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
