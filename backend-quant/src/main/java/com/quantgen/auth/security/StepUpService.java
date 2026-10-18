package com.quantgen.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;

/**
 * F-SEC-2 / D-029: step-up tokens for risky actions.
 *
 * <p>A service that needs extra proof calls {@link #require}; without a valid token for that exact
 * (user, action) it answers 401 STEP_UP_REQUIRED listing the methods the user can use. The client completes a
 * PIN (or passkey) check at {@code /auth/step-up/verify}, receives a token, and retries the original request.
 * Tokens are 256 random bits, stored only as a SHA-256 hash, valid for 5 minutes, and bound to one user and
 * one action. They are not single-use: a retry of the same request (same Idempotency-Key) must keep working
 * within the window, and the idempotency claim is what makes the money effect happen at most once.
 */
@Service
public class StepUpService {

    public static final Duration TTL = Duration.ofMinutes(5);
    public static final String HEADER = "X-Step-Up-Token";

    private final JdbcTemplate jdbc;
    private final PinService pins;
    private final AuditService audit;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public StepUpService(JdbcTemplate jdbc, PinService pins, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.pins = pins;
        this.audit = audit;
        this.clock = clock;
    }

    public record IssuedToken(String stepUpToken, long expiresIn) {
    }

    /** The methods this user can currently use, strongest first. Empty means they must set a PIN first. */
    @Transactional(readOnly = true)
    public List<String> methods(UUID userId) {
        List<String> methods = new ArrayList<>();
        if (hasPasskey(userId)) {
            methods.add("PASSKEY");
        }
        if (pins.hasPin(userId)) {
            methods.add("PIN");
        }
        return methods;
    }

    private boolean hasPasskey(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM passkeys WHERE user_id = ?)",
                Boolean.class, userId));
    }

    /** Throws 401 STEP_UP_REQUIRED unless {@code rawToken} is a live token for exactly this user and action. */
    @Transactional(readOnly = true, noRollbackFor = ApiException.class) // joins callers that commit on 4xx
    public void require(UUID userId, StepUpAction action, String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 200 || !isValid(userId, action, rawToken)) {
            throw ApiException.stepUpRequired(methods(userId), action.name());
        }
    }

    /** Whether the user needs a step-up token at all is the caller's policy; this just checks a presented one. */
    @Transactional(readOnly = true)
    public boolean isValid(UUID userId, StepUpAction action, String rawToken) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM step_up_tokens
                                WHERE token_hash = ? AND user_id = ? AND action = ? AND expires_at > ?)
                """, Boolean.class, hash(rawToken), userId, action.name(), java.sql.Timestamp.from(clock.instant())));
    }

    /** Verifies the PIN and issues a token for {@code action}. */
    @Transactional(noRollbackFor = ApiException.class)
    public IssuedToken verifyPin(UUID userId, StepUpAction action, String pin) {
        pins.verify(userId, pin);
        return issue(userId, action, "PIN");
    }

    /** Issues a token after the caller has verified the user by {@code method} ("PASSKEY" or "PIN"). */
    @Transactional
    public IssuedToken issue(UUID userId, StepUpAction action, String method) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.update("DELETE FROM step_up_tokens WHERE expires_at < ?", java.sql.Timestamp.from(now.minus(Duration.ofDays(1))));
        jdbc.update("INSERT INTO step_up_tokens (user_id, action, method, token_hash, expires_at) VALUES (?, ?, ?, ?, ?)",
                userId, action.name(), method, hash(raw), java.sql.Timestamp.from(now.plus(TTL)));
        audit.record(userId, "STEP_UP_GRANTED", "USER", userId.toString(), "action=" + action + " method=" + method);
        return new IssuedToken(raw, TTL.toSeconds());
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
