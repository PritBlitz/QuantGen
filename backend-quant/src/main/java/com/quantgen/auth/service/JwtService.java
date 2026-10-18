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
import com.quantgen.common.security.AuthUser;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;

/** Issues and verifies HS256 access tokens: {@code sub=userId, role, jti, iat, exp, iss}. */
@Service
public class JwtService implements AccessTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String ISSUER = "quantgen";
    private static final String ROLE_CLAIM = "role";
    private static final String SESSION_CLAIM = "sid";
    private static final int MIN_KEY_BYTES = 32;

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
