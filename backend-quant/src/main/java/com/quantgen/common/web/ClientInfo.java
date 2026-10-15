package com.quantgen.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Who is calling, for audit and abuse controls. The remote address honours forwarded headers only when
 * {@code server.forward-headers-strategy} is configured (prod, behind nginx).
 */
public record ClientInfo(String ip, String userAgent, String requestId) {

    private static final ClientInfo NONE = new ClientInfo(null, null, null);

    public static ClientInfo of(HttpServletRequest request) {
        return new ClientInfo(
                truncate(request.getRemoteAddr(), 45),
                truncate(request.getHeader(HttpHeaders.USER_AGENT), 255),
                RequestIdFilter.currentRequestId(request));
    }

    /** The current HTTP request's client info, or empty values outside a request (e.g. scheduled jobs). */
    public static ClientInfo current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return of(attrs.getRequest());
        }
        return NONE;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
