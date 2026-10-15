package com.quantgen.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.api.AuthDtos.RegisterRequest;
import com.quantgen.auth.api.AuthDtos.UserDto;
import com.quantgen.auth.domain.RefreshToken;
import com.quantgen.auth.domain.RefreshTokenRepository;
import com.quantgen.auth.domain.Role;
import com.quantgen.auth.domain.User;
import com.quantgen.auth.domain.UserRepository;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.web.ClientInfo;

/**
 * Registration, login (with lockout), refresh-token rotation with reuse detection, and logout.
 *
 * <p>Login and refresh run their database work in an explicit transaction that always commits (so a
 * failed-login counter or a family revocation is never rolled back) and raise errors only afterwards.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final String SESSION_EXPIRED = "Session expired; please log in again";

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final AccountService accountService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService audit;
    private final AuthProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String dummyPasswordHash;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, AccountService accountService,
                       PasswordEncoder passwordEncoder, JwtService jwtService, AuditService audit,
                       AuthProperties props, Clock clock, PlatformTransactionManager txManager) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.accountService = accountService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.audit = audit;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        // Used to spend the same BCrypt time for unknown emails, so timing doesn't reveal which exist.
        this.dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    /** A successful login or refresh: the access token, the raw refresh token for the cookie, and the user. */
    public record Session(JwtService.IssuedToken accessToken, String refreshToken, Duration refreshTtl, UserDto user) {

        @Override
        public String toString() {
            return "Session[user=" + user.id() + "]";
        }
    }

    // ------------------------------------------------------------------ register

    public UserDto register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        try {
            return tx.execute(status -> {
                if (users.existsByEmail(email)) {
                    throw duplicateEmail();
                }
                User user = users.saveAndFlush(new User(req.fullName().trim(), email, req.phone().trim(),
                        passwordEncoder.encode(req.password()), Role.CUSTOMER));
                accountService.openAccount(user.getId(), AccountType.SAVINGS); // F-AUTH-5
                audit.record(user.getId(), email, "USER_REGISTERED", "USER", user.getId().toString(), null);
                return toDto(user);
            });
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent registration for the same email.
            throw duplicateEmail();
        }
    }

    // ------------------------------------------------------------------ login

    private sealed interface LoginOutcome {
        record Success(Session session) implements LoginOutcome {
        }

        record Invalid() implements LoginOutcome {
        }

        record Locked(long retryAfterSeconds) implements LoginOutcome {
        }
    }

    public Session login(String rawEmail, String password, ClientInfo client) {
        String email = normalizeEmail(rawEmail);
        LoginOutcome outcome = tx.execute(status -> attemptLogin(email, password, client));
        return switch (outcome) {
            case LoginOutcome.Success s -> s.session();
            case LoginOutcome.Invalid i -> throw new ApiException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
            case LoginOutcome.Locked l -> throw ApiException.rateLimited(
                    "Too many failed logins. Try again in " + minutes(l.retryAfterSeconds()) + ".",
                    l.retryAfterSeconds());
            case null -> throw new IllegalStateException("login transaction returned no outcome");
        };
    }

    private LoginOutcome attemptLogin(String email, String password, ClientInfo client) {
        Instant now = clock.instant();
        Optional<User> found = users.findByEmailForUpdate(email);
        if (found.isEmpty()) {
            passwordEncoder.matches(password, dummyPasswordHash);
            audit.record(null, email, "LOGIN_FAILED", "USER", null, "unknown email");
            return new LoginOutcome.Invalid();
        }
        User user = found.get();
        if (user.isLocked(now)) {
            audit.record(user.getId(), email, "LOGIN_BLOCKED", "USER", user.getId().toString(), "account locked");
            return new LoginOutcome.Locked(secondsUntil(now, user.getLockedUntil()));
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            AuthProperties.Auth cfg = props.auth();
            boolean locked = user.recordFailedLogin(now, cfg.maxFailedLogins(), cfg.failedLoginWindow(),
                    cfg.lockoutDuration());
            audit.record(user.getId(), email, locked ? "LOGIN_LOCKED" : "LOGIN_FAILED", "USER",
                    user.getId().toString(), "bad password");
            return locked
                    ? new LoginOutcome.Locked(secondsUntil(now, user.getLockedUntil()))
                    : new LoginOutcome.Invalid();
        }
        user.recordSuccessfulLogin();
        Session session = startSession(user, UUID.randomUUID(), now, client).session();
        audit.record(user.getId(), email, "LOGIN_SUCCESS", "USER", user.getId().toString(), null);
        return new LoginOutcome.Success(session);
    }

    // ------------------------------------------------------------------ refresh

    private sealed interface RefreshOutcome {
        record Success(Session session) implements RefreshOutcome {
        }

        record Rejected() implements RefreshOutcome {
        }
    }

    public Session refresh(String rawToken, ClientInfo client) {
        if (!RefreshTokens.isPlausible(rawToken)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, SESSION_EXPIRED);
        }
        RefreshOutcome outcome = tx.execute(status -> attemptRefresh(RefreshTokens.hash(rawToken), client));
        if (outcome instanceof RefreshOutcome.Success s) {
            return s.session();
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED, SESSION_EXPIRED);
    }

    private RefreshOutcome attemptRefresh(String tokenHash, ClientInfo client) {
        Instant now = clock.instant();
        Optional<RefreshToken> found = refreshTokens.findByTokenHashForUpdate(tokenHash);
        if (found.isEmpty()) {
            return new RefreshOutcome.Rejected();
        }
        RefreshToken token = found.get();
        if (token.isRevoked()) {
            // F-AUTH-3: a revoked token was presented again, so it may have been stolen. Kill the family.
            int revoked = refreshTokens.revokeFamily(token.getFamilyId(), now);
            log.warn("Refresh token reuse detected for user {}; revoked {} active token(s) in family",
                    token.getUserId(), revoked);
            audit.record(token.getUserId(), "REFRESH_TOKEN_REUSE", "USER", token.getUserId().toString(),
                    "family revoked");
            return new RefreshOutcome.Rejected();
        }
        if (token.isExpired(now)) {
            return new RefreshOutcome.Rejected();
        }
        Optional<User> user = users.findById(token.getUserId());
        if (user.isEmpty()) {
            return new RefreshOutcome.Rejected();
        }
        Started started = startSession(user.get(), token.getFamilyId(), now, client);
        token.revoke(now, started.refreshTokenId());
        return new RefreshOutcome.Success(started.session());
    }

    // ------------------------------------------------------------------ logout

    /** Revokes the session's token family. Idempotent: unknown or already-revoked tokens are ignored. */
    public void logout(String rawToken) {
        if (!RefreshTokens.isPlausible(rawToken)) {
            return;
        }
        tx.executeWithoutResult(status -> refreshTokens.findByTokenHashForUpdate(RefreshTokens.hash(rawToken))
                .filter(t -> !t.isRevoked())
                .ifPresent(t -> {
                    refreshTokens.revokeFamily(t.getFamilyId(), clock.instant());
                    audit.record(t.getUserId(), "LOGOUT", "USER", t.getUserId().toString(), null);
                }));
    }

    // ------------------------------------------------------------------ helpers

    private record Started(Session session, UUID refreshTokenId) {
    }

    private Started startSession(User user, UUID familyId, Instant now, ClientInfo client) {
        String raw = RefreshTokens.newToken();
        Duration ttl = props.jwt().refreshTtl();
        RefreshToken saved = refreshTokens.saveAndFlush(new RefreshToken(user.getId(), familyId,
                RefreshTokens.hash(raw), now.plus(ttl), client.ip(), client.userAgent()));
        Session session = new Session(jwtService.issue(user.getId(), user.getRole()), raw, ttl, toDto(user));
        return new Started(session, saved.getId());
    }

    static UserDto toDto(User u) {
        return new UserDto(u.getId(), u.getFullName(), u.getEmail(), u.getPhone(), u.getRole(), u.getCreatedAt());
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException duplicateEmail() {
        return ApiException.fieldError(HttpStatus.CONFLICT, "email", "is already registered");
    }

    private static long secondsUntil(Instant now, Instant until) {
        return Math.max(1, Duration.between(now, until).toSeconds());
    }

    private static String minutes(long seconds) {
        long m = Math.max(1, (seconds + 59) / 60);
        return m == 1 ? "1 minute" : m + " minutes";
    }
}
