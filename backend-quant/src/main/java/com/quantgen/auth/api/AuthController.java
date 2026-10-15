package com.quantgen.auth.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.api.AuthDtos.AuthResponse;
import com.quantgen.auth.api.AuthDtos.LoginRequest;
import com.quantgen.auth.api.AuthDtos.RegisterRequest;
import com.quantgen.auth.api.AuthDtos.UserDto;
import com.quantgen.auth.service.AuthService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.web.ClientInfo;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.2 auth endpoints. All public; refresh and logout authenticate with the refresh cookie. */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
@SecurityRequirements
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies cookies;

    public AuthController(AuthService authService, RefreshCookies cookies) {
        this.authService = authService;
        this.cookies = cookies;
    }

    @PostMapping("/register")
    @Operation(summary = "Register a customer; also opens a SAVINGS account. No tokens are issued.")
    public ResponseEntity<UserDto> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Log in. Returns an access token and sets the qg_refresh cookie.")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return sessionResponse(authService.login(request.email(), request.password(), ClientInfo.of(http)));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate the refresh cookie and return a new access token.")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken,
            HttpServletRequest http, HttpServletResponse response) {
        try {
            return sessionResponse(authService.refresh(refreshToken, ClientInfo.of(http)));
        } catch (ApiException e) {
            response.addHeader(HttpHeaders.SET_COOKIE, cookies.clear());
            throw e;
        }
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the refresh token family and clear the cookie.")
    public ResponseEntity<Void> logout(@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookies.clear()).build();
    }

    private ResponseEntity<AuthResponse> sessionResponse(AuthService.Session session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.issue(session.refreshToken(), session.refreshTtl()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AuthResponse(session.accessToken().token(), session.accessToken().expiresInSeconds(),
                        session.user()));
    }
}
