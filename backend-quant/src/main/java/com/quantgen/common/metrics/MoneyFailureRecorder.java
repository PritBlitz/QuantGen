package com.quantgen.common.metrics;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.quantgen.common.security.AuthUser;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Records which money endpoint failed with which error code (PRD F-ANL-1/3/8). It stores the endpoint pattern,
 * the code, the status and the user id, never a body, an amount or an account number. A prompt for more
 * verification (401 STEP_UP_REQUIRED) is not a failure, and unauthenticated requests are not recorded. Recording
 * is best-effort: a database problem here must never change the response the customer gets.
 */
@Component
public class MoneyFailureRecorder {

    private static final Logger log = LoggerFactory.getLogger(MoneyFailureRecorder.class);

    private static final Map<Pattern, String> MONEY_ENDPOINTS = Map.ofEntries(
            Map.entry(Pattern.compile("^/api/v1/transfers$"), "POST /transfers"),
            Map.entry(Pattern.compile("^/api/v1/orders$"), "POST /orders"),
            Map.entry(Pattern.compile("^/api/v1/accounts/[^/]+/deposit$"), "POST /accounts/{id}/deposit"),
            Map.entry(Pattern.compile("^/api/v1/loans/[^/]+/repay$"), "POST /loans/{id}/repay"),
            Map.entry(Pattern.compile("^/api/v1/payment-requests/[^/]+/pay$"), "POST /payment-requests/{id}/pay"),
            Map.entry(Pattern.compile("^/api/v1/bill-payments$"), "POST /bill-payments"),
            Map.entry(Pattern.compile("^/api/v1/topups/[^/]+/verify$"), "POST /topups/{id}/verify"));

    private final JdbcTemplate jdbc;

    public MoneyFailureRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(HttpServletRequest request, int status, String code) {
        try {
            if (status < 400 || !"POST".equals(request.getMethod()) || "UNAUTHORIZED".equals(code)
                    || "STEP_UP_REQUIRED".equals(code)) {
                return;
            }
            String endpoint = MONEY_ENDPOINTS.entrySet().stream()
                    .filter(e -> e.getKey().matcher(request.getRequestURI()).matches())
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            if (endpoint == null) {
                return;
            }
            jdbc.update("INSERT INTO request_failures (endpoint, code, status, user_id) VALUES (?, ?, ?, ?)",
                    endpoint, code, status, currentUser());
        } catch (RuntimeException e) {
            log.warn("Could not record a failed money request", e);
        }
    }

    private static UUID currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthUser user ? user.id() : null;
    }
}
