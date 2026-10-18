package com.quantgen.common.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quantgen.common.error.ApiException;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;

/**
 * Per-user token buckets for signed-in endpoints that could be abused one request at a time, such as payee
 * lookups (F-PAY-6, enumeration). Unlike {@link RateLimitFilter} it keys on the user, not the IP, and is always
 * on. Over the limit: 429 RATE_LIMITED with {@code Retry-After}. In memory (one instance); idle buckets are evicted.
 */
@Component
public class UserRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(UserRateLimiter.class);
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(30);

    /** {@code capacity} calls, refilled continuously over {@code period}. */
    public record Limit(String name, int capacity, Duration period) {
    }

    private record Entry(Bucket bucket, long[] lastSeen) {
    }

    private final Map<String, Entry> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public UserRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Takes one token for (limit, user) or throws 429. */
    public void acquire(Limit limit, UUID userId) {
        Entry entry = buckets.computeIfAbsent(limit.name() + "|" + userId,
                k -> new Entry(Bucket.builder().addLimit(Bandwidth.builder().capacity(limit.capacity())
                        .refillGreedy(limit.capacity(), limit.period()).build()).build(), new long[1]));
        entry.lastSeen()[0] = clock.millis();
        ConsumptionProbe probe = entry.bucket().tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            long retryAfter = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
            log.warn("Rate limit '{}' exceeded by user {}", limit.name(), userId);
            throw ApiException.rateLimited("Too many requests; try again in " + retryAfter + " seconds", retryAfter);
        }
    }

    @Scheduled(fixedDelay = 5, timeUnit = TimeUnit.MINUTES)
    void evictIdle() {
        long cutoff = clock.millis() - IDLE_EVICTION.toMillis();
        buckets.entrySet().removeIf(e -> e.getValue().lastSeen()[0] < cutoff);
    }
}
