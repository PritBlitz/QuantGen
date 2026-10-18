package com.quantgen.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.quantgen.common.error.ApiErrorWriter;

import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    private RateLimitFilter filter(boolean enabled) {
        return new RateLimitFilter(new ApiErrorWriter(json, clock, new com.quantgen.common.metrics.MoneyFailureRecorder(null)), clock, enabled);
    }

    private static MockHttpServletResponse call(RateLimitFilter filter, String uri, String ip) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", uri);
        req.setRemoteAddr(ip);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void loginIsLimitedPerIpWithRetryAfterAndStandardBody() throws Exception {
        RateLimitFilter f = filter(true);
        for (int i = 0; i < RateLimitFilter.LOGIN.capacity(); i++) {
            assertThat(call(f, "/api/v1/auth/login", "10.0.0.1").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(f, "/api/v1/auth/login", "10.0.0.1");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(json.readTree(limited.getContentAsString()).get("code").asString()).isEqualTo("RATE_LIMITED");
        assertThat(json.readTree(limited.getContentAsString()).get("path").asString()).isEqualTo("/api/v1/auth/login");

        // Other IPs and other auth endpoints have their own buckets.
        assertThat(call(f, "/api/v1/auth/login", "10.0.0.2").getStatus()).isEqualTo(200);
        assertThat(call(f, "/api/v1/auth/refresh", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void registerHasItsOwnStricterWindow() throws Exception {
        RateLimitFilter f = filter(true);
        for (int i = 0; i < RateLimitFilter.REGISTER.capacity(); i++) {
            assertThat(call(f, "/api/v1/auth/register", "10.0.0.3").getStatus()).isEqualTo(200);
        }
        MockHttpServletResponse limited = call(f, "/api/v1/auth/register", "10.0.0.3");
        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(limited.getHeader("Retry-After"))).isGreaterThan(60L);
    }

    @Test
    void nonAuthPathsAndDisabledFilterAreUntouched() throws Exception {
        RateLimitFilter f = filter(true);
        for (int i = 0; i < 100; i++) {
            assertThat(call(f, "/api/v1/accounts", "10.0.0.4").getStatus()).isEqualTo(200);
        }
        assertThat(f.trackedClients()).isZero();

        RateLimitFilter off = filter(false);
        for (int i = 0; i < 50; i++) {
            assertThat(call(off, "/api/v1/auth/login", "10.0.0.5").getStatus()).isEqualTo(200);
        }
    }

    @Test
    void rulesByPath() {
        assertThat(RateLimitFilter.ruleFor("/api/v1/auth/login")).isEqualTo(RateLimitFilter.LOGIN);
        assertThat(RateLimitFilter.ruleFor("/api/v1/auth/register")).isEqualTo(RateLimitFilter.REGISTER);
        assertThat(RateLimitFilter.ruleFor("/api/v1/auth/refresh")).isEqualTo(RateLimitFilter.REFRESH);
        assertThat(RateLimitFilter.ruleFor("/api/v1/auth/logout")).isEqualTo(RateLimitFilter.OTHER);
    }
}
