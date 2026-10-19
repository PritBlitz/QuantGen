package com.quantgen.auth.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.security.PersonalTokenAuthenticator;
import com.quantgen.common.security.UserRateLimiter;

/**
 * F-MCP-2 personal access tokens: named, scoped ({@code read} or {@code read+propose}), expiring in at most 90 days,
 * shown once, stored as a SHA-256, revocable, and audited on every use. What a token can reach is decided by
 * {@code PatAuthenticationFilter}.
 */
@Service
public class PersonalAccessTokenService implements PersonalTokenAuthenticator {

    static final int MAX_ACTIVE = 10;
    static final UserRateLimiter.Limit USE_LIMIT = new UserRateLimiter.Limit("pat-use", 60, Duration.ofMinutes(1));
    static final List<String> SCOPES = List.of("read", "read+propose");

    public record TokenDto(UUID id, String name, String scope, Instant lastUsedAt, Instant expiresAt) {
    }

    public record CreatedTokenDto(UUID id, String token, String name, String scope, Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final StepUpService stepUp;
    private final AuditService audit;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    PersonalAccessTokenService(JdbcTemplate jdbc, StepUpService stepUp, AuditService audit, UserRateLimiter rateLimiter,
                               Clock clock) {
        this.jdbc = jdbc;
        this.stepUp = stepUp;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Always stepped up (TOKEN_CREATE). Customers only: an admin's authority never leaves the console. */
    @Transactional(noRollbackFor = ApiException.class)
    public CreatedTokenDto create(AuthUser user, String name, String scope, int expiresInDays, String stepUpToken) {
        if (user.isAdmin()) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Admins can't create personal access tokens");
        }
        if (!SCOPES.contains(scope)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "scope", "must be read or read+propose");
        }
        if (expiresInDays < 1 || expiresInDays > 90) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "expiresInDays", "must be 1–90");
        }
        stepUp.require(user.id(), StepUpAction.TOKEN_CREATE, stepUpToken);
        // Serialize this user's creations so the cap holds under concurrent requests.
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, user.id());
        Instant now = clock.instant();
        Integer active = jdbc.queryForObject("SELECT count(*) FROM personal_access_tokens WHERE user_id = ? "
                + "AND revoked_at IS NULL AND expires_at > ?", Integer.class, user.id(), Timestamp.from(now));
        if (active != null && active >= MAX_ACTIVE) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "At most " + MAX_ACTIVE + " active tokens; revoke one first");
        }
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String token = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        Instant expiresAt = now.plus(Duration.ofDays(expiresInDays));
        UUID id = jdbc.queryForObject("""
                INSERT INTO personal_access_tokens (user_id, name, scope, token_hash, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING id
                """, UUID.class, user.id(), name, scope, hash(token), Timestamp.from(now), Timestamp.from(expiresAt));
        audit.record(user.id(), "TOKEN_CREATED", "TOKEN", id.toString(), name + " (" + scope + ", " + expiresInDays + " days)");
        return new CreatedTokenDto(id, token, name, scope, expiresAt);
    }

    /** Active and past tokens are both listed until they're revoked; the token value is never returned again. */
    @Transactional(readOnly = true)
    public List<TokenDto> list(UUID userId) {
        return jdbc.query("""
                SELECT id, name, scope, last_used_at, expires_at FROM personal_access_tokens
                 WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at DESC, id
                """, (rs, i) -> {
            Timestamp used = rs.getTimestamp("last_used_at");
            return new TokenDto(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("scope"),
                    used == null ? null : used.toInstant(), rs.getTimestamp("expires_at").toInstant());
        }, userId);
    }

    @Transactional
    public void revoke(UUID userId, UUID tokenId) {
        int n = jdbc.update("UPDATE personal_access_tokens SET revoked_at = ? WHERE id = ? AND user_id = ? "
                + "AND revoked_at IS NULL", Timestamp.from(clock.instant()), tokenId, userId);
        if (n == 0) {
            throw ApiException.notFound("Token");
        }
        audit.record(userId, "TOKEN_REVOKED", "TOKEN", tokenId.toString(), null);
    }

    // ------------------------------------------------------------------ PersonalTokenAuthenticator

    @Override
    @Transactional(readOnly = true)
    public Optional<TokenPrincipal> authenticate(String rawToken) {
        return jdbc.query("""
                SELECT t.id, t.user_id, t.scope FROM personal_access_tokens t JOIN users u ON u.id = t.user_id
                 WHERE t.token_hash = ? AND t.revoked_at IS NULL AND t.expires_at > ? AND u.role = 'CUSTOMER'
                """, (rs, i) -> new TokenPrincipal(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("scope")), hash(rawToken), Timestamp.from(clock.instant())).stream().findFirst();
    }

    @Override
    @Transactional
    public void recordUse(TokenPrincipal pat, String method, String path, boolean allowed) {
        if (allowed) {
            jdbc.update("UPDATE personal_access_tokens SET last_used_at = ? WHERE id = ?", Timestamp.from(clock.instant()),
                    pat.tokenId());
        }
        // Method and path only: query strings can carry search terms and are not needed to trace a use.
        audit.record(pat.userId(), allowed ? "TOKEN_USED" : "TOKEN_DENIED", "TOKEN", pat.tokenId().toString(),
                method + " " + (path.length() > 200 ? path.substring(0, 200) : path));
    }

    @Override
    public void rateLimit(TokenPrincipal pat) {
        rateLimiter.acquire(USE_LIMIT, pat.tokenId());
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
