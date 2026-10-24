package com.quantgen.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.quantgen.auth.domain.Role;
import com.quantgen.common.security.AccessTokenVerifier;
import com.quantgen.common.security.AgentTokenVerifier;
import com.quantgen.common.security.AuthUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and verifies HS256 access tokens: {@code sub=userId, role, jti, iat, exp, iss}. Also delegated agent tokens
 * (D-022): {@code aud=agent, act=agent-quant, scope, conv}, 15 minutes. A token with an audience is never accepted as
 * an access token, so an agent token can't be used as the user's own.
 */
@Service
public class JwtService implements AccessTokenVerifier, AgentTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "quantgen";
    private static final String ROLE_CLAIM = "role";
    private static final String SESSION_CLAIM = "sid";
    private static final int MIN_KEY_BYTES = 32;
    static final String AGENT_AUDIENCE = "agent";
    static final java.time.Duration AGENT_TTL = java.time.Duration.ofMinutes(15);

    private final SecretKey key;
    private final AuthProperties props;
    private final Clock clock;

    public JwtService(AuthProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.key = buildKey(props.jwt().secret());
    }

    public record IssuedToken(String token, long expiresInSeconds) {
    }

    public IssuedToken issue(UUID userId, Role role) {
        return issue(userId, role, null);
    }

    /** @param sessionId the refresh-token family, so the session list can mark the caller's own session */
    public IssuedToken issue(UUID userId, Role role, UUID sessionId) {
        Instant now = clock.instant();
        Instant exp = now.plus(props.jwt().accessTtl());
        String token = Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(ROLE_CLAIM, role.name())
                .claim(SESSION_CLAIM, sessionId == null ? null : sessionId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, props.jwt().accessTtl().toSeconds());
    }

    @Override
    public Optional<AuthUser> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getAudience() != null && !claims.getAudience().isEmpty()) {
                return Optional.empty(); // a delegated agent token is never a user's access token
            }
            UUID userId = UUID.fromString(claims.getSubject());
            Role role = Role.valueOf(claims.get(ROLE_CLAIM, String.class));
            String sid = claims.get(SESSION_CLAIM, String.class);
            return Optional.of(new AuthUser(userId, role.name(), sid == null ? null : UUID.fromString(sid)));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            // Never log the token itself.
            log.debug("Rejected access token: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** A delegated token for one conversation turn (D-022): read + propose only, enforced by the security chain. */
    public String issueAgentToken(UUID userId, UUID conversationId) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .audience().add(AGENT_AUDIENCE).and()
                .claim("act", "agent-quant")
                .claim("scope", "agent:read agent:propose")
                .claim("conv", conversationId.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(AGENT_TTL)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    @Override
    public Optional<AgentPrincipal> verifyAgent(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .requireAudience(AGENT_AUDIENCE)
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AgentPrincipal(UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get("conv", String.class))));
        } catch (JwtException | IllegalArgumentException | NullPointerException e) {
            log.debug("Rejected agent token: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static SecretKey buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("qg.jwt.secret (QG_JWT_SECRET) must be set");
        }
        byte[] bytes;
        try {
            bytes = Decoders.BASE64.decode(secret.trim());
        } catch (RuntimeException e) {
            throw new IllegalStateException("qg.jwt.secret (QG_JWT_SECRET) must be base64", e);
        }
        if (bytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("qg.jwt.secret (QG_JWT_SECRET) must decode to at least 32 bytes");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
