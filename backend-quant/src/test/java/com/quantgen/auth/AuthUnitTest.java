package com.quantgen.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.auth.api.StrongPassword;
import com.quantgen.auth.domain.Role;
import com.quantgen.auth.domain.User;
import com.quantgen.auth.service.AuthProperties;
import com.quantgen.auth.service.JwtService;

class AuthUnitTest {

    private static final String SECRET = "dGVzdC1vbmx5LXF1YW50Z2VuLWp3dC1zZWNyZXQtbm90LWZvci1wcm9kdWN0aW9uLXVzZS0wMTIzNDU2Nzg5";
    private static final Duration W = Duration.ofMinutes(15);
    private final Instant t0 = Instant.parse("2026-10-16T10:00:00Z");

    @Test
    void fiveFailuresWithinWindowLockForFifteenMinutes() {
        User u = new User("A", "a@x.dev", "9999999999", "hash", Role.CUSTOMER);
        for (int i = 0; i < 4; i++) {
            assertThat(u.recordFailedLogin(t0.plusSeconds(60L * i), 5, W, W)).isFalse();
        }
        assertThat(u.recordFailedLogin(t0.plusSeconds(240), 5, W, W)).isTrue();
        assertThat(u.isLocked(t0.plusSeconds(240))).isTrue();
        assertThat(u.isLocked(t0.plusSeconds(240).plus(W).minusSeconds(1))).isTrue();
        assertThat(u.isLocked(t0.plusSeconds(240).plus(W))).isFalse();
    }

    @Test
    void failuresOutsideWindowStartANewCount() {
        User u = new User("A", "a@x.dev", "9999999999", "hash", Role.CUSTOMER);
        for (int i = 0; i < 4; i++) {
            u.recordFailedLogin(t0, 5, W, W);
        }
        // The window has passed, so this is failure #1 of a new window.
        assertThat(u.recordFailedLogin(t0.plus(W), 5, W, W)).isFalse();
        assertThat(u.getFailedLoginCount()).isEqualTo(1);
    }

    @Test
    void successResetsFailures() {
        User u = new User("A", "a@x.dev", "9999999999", "hash", Role.CUSTOMER);
        u.recordFailedLogin(t0, 5, W, W);
        u.recordSuccessfulLogin();
        assertThat(u.getFailedLoginCount()).isZero();
        assertThat(u.getLockedUntil()).isNull();
    }

    @Test
    void passwordPolicy() {
        var v = new StrongPassword.Validator();
        assertThat(v.isValid("Demo@1234", null)).isTrue();
        assertThat(v.isValid("demo@1234", null)).as("no uppercase").isFalse();
        assertThat(v.isValid("DEMO@1234", null)).as("no lowercase").isFalse();
        assertThat(v.isValid("Demo@abcd", null)).as("no digit").isFalse();
        assertThat(v.isValid("Demo12345", null)).as("no symbol").isFalse();
        assertThat(v.isValid("De@1234", null)).as("too short").isFalse();
        assertThat(v.isValid("Aa1!" + "x".repeat(69), null)).as("over 72 bytes").isFalse();
        assertThat(v.isValid("Aa1!" + "x".repeat(68), null)).as("exactly 72 bytes").isTrue();
    }

    @Test
    void accessTokenRoundTripsAndExpires() {
        AuthProperties props = new AuthProperties(new AuthProperties.Jwt(SECRET, null, null), null);
        JwtService issuer = new JwtService(props, Clock.fixed(t0, ZoneOffset.UTC));
        UUID userId = UUID.randomUUID();
        var token = issuer.issue(userId, Role.ADMIN);
        assertThat(token.expiresInSeconds()).isEqualTo(900);

        var user = issuer.verify(token.token()).orElseThrow();
        assertThat(user.id()).isEqualTo(userId);
        assertThat(user.role()).isEqualTo("ADMIN");

        JwtService later = new JwtService(props, Clock.fixed(t0.plus(Duration.ofMinutes(16)), ZoneOffset.UTC));
        assertThat(later.verify(token.token())).isEmpty();
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        AuthProperties props = new AuthProperties(new AuthProperties.Jwt(SECRET, null, null), null);
        JwtService jwt = new JwtService(props, Clock.systemUTC());
        String token = jwt.issue(UUID.randomUUID(), Role.CUSTOMER).token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "BB" : "AA");
        assertThat(jwt.verify(tampered)).isEmpty();
        assertThat(jwt.verify("not-a-jwt")).isEmpty();

        String otherSecret = "b3RoZXItc2VjcmV0LW90aGVyLXNlY3JldC1vdGhlci1zZWNyZXQtb3RoZXItc2VjcmV0";
        JwtService other = new JwtService(new AuthProperties(new AuthProperties.Jwt(otherSecret, null, null), null),
                Clock.systemUTC());
        assertThat(jwt.verify(other.issue(UUID.randomUUID(), Role.ADMIN).token())).isEmpty();
    }

    @Test
    void weakOrMissingSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService(new AuthProperties(null, null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new JwtService(
                new AuthProperties(new AuthProperties.Jwt("c2hvcnQ=", null, null), null), Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
