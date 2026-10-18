package com.quantgen.auth.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

/**
 * F-SEC-4: one session per refresh-token family. Revoking a session revokes its whole family, so its refresh
 * token stops working at once; an access token already issued to it stays valid until it expires (at most 15
 * minutes), which is the usual stateless-JWT trade-off.
 */
@Service
public class SessionService {

    /** PRD 12.3 SessionDto. {@code ipCity} is null: no GeoIP database is bundled. */
    public record SessionDto(UUID id, String device, String browser, String ipCity, boolean current, Instant createdAt,
                             Instant lastUsedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Clock clock;

    public SessionService(JdbcTemplate jdbc, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SessionDto> list(UUID userId, UUID currentSessionId) {
        return jdbc.query("""
                SELECT family_id, MIN(created_at) AS created, MAX(created_at) AS last_used,
                       (ARRAY_AGG(user_agent ORDER BY created_at DESC))[1] AS ua
                  FROM refresh_tokens WHERE user_id = ?
                 GROUP BY family_id
                HAVING BOOL_OR(revoked_at IS NULL AND expires_at > ?)
                 ORDER BY MAX(created_at) DESC
                """, (rs, i) -> {
            UUID id = rs.getObject("family_id", UUID.class);
            UserAgents.Parsed ua = UserAgents.parse(rs.getString("ua"));
            return new SessionDto(id, ua.device(), ua.browser(), null, id.equals(currentSessionId),
                    rs.getTimestamp("created").toInstant(), rs.getTimestamp("last_used").toInstant());
        }, userId, java.sql.Timestamp.from(clock.instant()));
    }

    /** Revokes one of the caller's sessions. Someone else's (or an unknown) id is 404. */
    @Transactional
    public void revoke(UUID userId, UUID sessionId) {
        int n = jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND family_id = ? "
                + "AND revoked_at IS NULL", java.sql.Timestamp.from(clock.instant()), userId, sessionId);
        if (n == 0) {
            throw ApiException.notFound("Session");
        }
        audit.record(userId, "SESSION_REVOKED", "SESSION", sessionId.toString(), null);
    }

    /** Revokes every session except the caller's own; returns how many were revoked. */
    @Transactional
    public int revokeOthers(UUID userId, UUID currentSessionId) {
        if (currentSessionId == null) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Sign in again before managing other sessions");
        }
        int n = jdbc.update("UPDATE refresh_tokens SET revoked_at = ? WHERE user_id = ? AND family_id <> ? "
                + "AND revoked_at IS NULL", java.sql.Timestamp.from(clock.instant()), userId, currentSessionId);
        audit.record(userId, "SESSIONS_REVOKED_OTHERS", "USER", userId.toString(), "tokens=" + n);
        return n;
    }

    /** True if the user has signed in before, but never from this user agent (F-SEC-4 new-device notice). */
    @Transactional(readOnly = true)
    public boolean isNewDevice(UUID userId, String userAgent) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM refresh_tokens WHERE user_id = ?)
                   AND NOT EXISTS (SELECT 1 FROM refresh_tokens WHERE user_id = ? AND user_agent IS NOT DISTINCT FROM ?)
                """, Boolean.class, userId, userId, userAgent));
    }
}
