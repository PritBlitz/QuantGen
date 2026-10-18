package com.quantgen.auth.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.quantgen.auth.api.AuthDtos.AuthResponse;
import com.quantgen.auth.service.AuthService;

/** The one place that turns a new {@link AuthService.Session} into the login response and refresh cookie. */
@Component
class SessionResponses {

    private final RefreshCookies cookies;

    SessionResponses(RefreshCookies cookies) {
        this.cookies = cookies;
    }

    ResponseEntity<AuthResponse> of(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken(), session.refreshTtl()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(session.accessToken().token(), session.accessToken().expiresInSeconds(),
                        session.user()));
    }
}
