package com.quantgen.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sets or echoes {@code X-Request-Id} on every request and exposes it to logs via MDC. Runs before
 * the security chain, so even rejected requests carry a correlation id.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_REQUEST_ID = "requestId";
    public static final String MDC_PATH = "path";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    /** Client-supplied ids are accepted only if short and log-safe (no CR/LF or spaces). */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        MDC.put(MDC_REQUEST_ID, requestId);
        MDC.put(MDC_PATH, request.getRequestURI());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_PATH);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // Keep the original id (and MDC) when the container forwards to /error.
        return false;
    }

    public static String currentRequestId(HttpServletRequest request) {
        Object id = request.getAttribute(ATTRIBUTE);
        if (id instanceof String s) {
            return s;
        }
        return MDC.get(MDC_REQUEST_ID);
    }
}
