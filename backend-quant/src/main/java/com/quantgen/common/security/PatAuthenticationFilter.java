package com.quantgen.common.security;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.quantgen.common.error.ApiErrorWriter;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.security.PersonalTokenAuthenticator.TokenPrincipal;

/**
 * Authenticates {@code Authorization: Bearer qg_pat_…} (PRD 12.12, F-MCP-2) and enforces the token's scope here,
 * deny by default: a personal access token reaches only the read routes below (plus proposing an intent with
 * {@code read+propose}); everything else, including minting tokens, admin and any money movement, is 403.
 * An unknown, expired or revoked token is 401. Every use is audited.
 *
 * <p>Not a {@code @Component}: it is added to the security chain only, after {@link JwtAuthenticationFilter}.
 */
public class PatAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute holding the id of the personal access token that authenticated the request. */
    public static final String TOKEN_ID_ATTRIBUTE = "qg.patTokenId";
    private static final String BEARER = "Bearer ";
    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /** GET routes a {@code read} token may call: the customer's own data, never secrets or exports. */
    static final List<String> READ = List.of(
            "/api/v1/users/me",
            "/api/v1/accounts", "/api/v1/accounts/*", "/api/v1/accounts/*/transactions",
            "/api/v1/transfers", "/api/v1/scheduled-transfers", "/api/v1/payment-requests",
            "/api/v1/fixed-deposits", "/api/v1/fixed-deposits/*",
            "/api/v1/loans", "/api/v1/loans/*", "/api/v1/loans/*/schedule",
            "/api/v1/portfolio", "/api/v1/orders", "/api/v1/watchlists",
            "/api/v1/market/instruments", "/api/v1/market/instruments/**",
            "/api/v1/insights/**", "/api/v1/analytics/**", "/api/v1/budgets",
            "/api/v1/payees/resolve");
    /** POST routes a {@code read+propose} token may also call. Intents still need approval in the app. */
    static final List<String> PROPOSE = List.of("/api/v1/intents");

    private final PersonalTokenAuthenticator tokens;
    private final ApiErrorWriter errors;

    public PatAuthenticationFilter(PersonalTokenAuthenticator tokens, ApiErrorWriter errors) {
        this.tokens = tokens;
        this.errors = errors;
    }

    static boolean allows(String scope, String method, String path) {
        if ("GET".equals(method)) {
            return READ.stream().anyMatch(p -> PATHS.match(p, path));
        }
        return "POST".equals(method) && "read+propose".equals(scope) && PROPOSE.stream().anyMatch(p -> PATHS.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            chain.doFilter(request, response);
            return;
        }
        String raw = header.substring(BEARER.length()).trim();
        if (!raw.startsWith(PersonalTokenAuthenticator.PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        if (!tokens.enabled()) {
            errors.write(request, response, ErrorCode.FEATURE_DISABLED);
            return;
        }
        Optional<TokenPrincipal> found = raw.length() > 100 ? Optional.empty() : tokens.authenticate(raw);
        if (found.isEmpty()) {
            errors.write(request, response, ErrorCode.UNAUTHORIZED);
            return;
        }
        TokenPrincipal pat = found.get();
        String path = request.getRequestURI();
        if (!allows(pat.scope(), request.getMethod(), path)) {
            tokens.recordUse(pat, request.getMethod(), path, false);
            errors.write(request, response, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                    "Personal access tokens can't be used for this request");
            return;
        }
        try {
            tokens.rateLimit(pat);
        } catch (ApiException e) {
            errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS, ErrorCode.RATE_LIMITED, e.getMessage());
            return;
        }
        tokens.recordUse(pat, request.getMethod(), path, true);
        request.setAttribute(TOKEN_ID_ATTRIBUTE, pat.tokenId()); // so an intent can record which token proposed it
        AuthUser user = new AuthUser(pat.userId(), AuthUser.ROLE_CUSTOMER);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + AuthUser.ROLE_CUSTOMER))));
        MDC.put(JwtAuthenticationFilter.MDC_USER_ID, pat.userId().toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(JwtAuthenticationFilter.MDC_USER_ID);
        }
    }
}
