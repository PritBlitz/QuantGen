package com.quantgen.common.security;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.quantgen.common.error.ApiErrorWriter;
import com.quantgen.common.error.ErrorCode;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Per-IP token buckets on {@code /api/v1/auth/**} (ARCHITECTURE §3, §9). Runs right after the request-id
 * filter and before Spring Security, so rejected requests never reach password hashing. Over the limit:
 * 429 RATE_LIMITED with {@code Retry-After}. Complements the per-account login lockout (F-AUTH-6).
 *
 * <p>The client IP is {@code request.getRemoteAddr()}, which honours X-Forwarded-For only when
 * {@code server.forward-headers-strategy} is set (prod, behind nginx), so clients can't spoof it.
 * Buckets are in memory (one instance); idle ones are evicted every few minutes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String PREFIX = "/api/v1/auth/";
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(30);

    /** A named limit: {@code capacity} requests, refilled continuously over {@code period}. */
    record Rule(String name, int capacity, Duration period) {
    }

    static final Rule LOGIN = new Rule("login", 10, Duration.ofMinutes(1));
    static final Rule REGISTER = new Rule("register", 10, Duration.ofHours(1));
    static final Rule REFRESH = new Rule("refresh", 30, Duration.ofMinutes(1));
    static final Rule OTHER = new Rule("auth", 30, Duration.ofMinutes(1));

    private record Entry(Bucket bucket, long[] lastSeen) {
    }

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final ApiErrorWriter errorWriter;
    private final Clock clock;
    private final boolean enabled;

    public RateLimitFilter(ApiErrorWriter errorWriter, Clock clock,
                           @Value("${qg.rate-limit.enabled:true}") boolean enabled) {
        this.errorWriter = errorWriter;
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !enabled || !request.getRequestURI().startsWith(PREFIX) || "OPTIONS".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Rule rule = ruleFor(request.getRequestURI());
        String ip = request.getRemoteAddr();
        Entry entry = buckets.computeIfAbsent(rule.name() + "|" + ip, k -> new Entry(newBucket(rule), new long[1]));
        entry.lastSeen()[0] = clock.millis();

        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        log.warn("Rate limit '{}' exceeded by {} on {}", rule.name(), ip, request.getRequestURI());
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        errorWriter.write(request, response, ErrorCode.RATE_LIMITED.status(), ErrorCode.RATE_LIMITED,
                "Too many requests; try again in " + retryAfter + " seconds");
    }

    static Rule ruleFor(String uri) {
        String rest = uri.substring(PREFIX.length());
        return switch (rest) {
            case "login" -> LOGIN;
            case "register" -> REGISTER;
            case "refresh" -> REFRESH;
            default -> OTHER;
        };
    }

    private static Bucket newBucket(Rule rule) {
        return Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(rule.capacity()).refillGreedy(rule.capacity(), rule.period()).build())
                .build();
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void evictIdle() {
        long cutoff = clock.millis() - IDLE_EVICTION.toMillis();
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen()[0] < cutoff);
    }

    int trackedClients() {
        return buckets.size();
    }
}
