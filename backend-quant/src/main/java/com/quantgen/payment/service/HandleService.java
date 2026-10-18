package com.quantgen.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.security.UserRateLimiter;

/**
 * F-PAY-5 handles ({@code name@qg}) and F-PAY-6 confirmation of payee. A handle points at one of its owner's
 * accounts; paying a handle pays that account. A handle can change once per 30 days, and one that was given up
 * stays reserved for its old owner for 30 days, so nobody can take it over and receive payments meant for them.
 */
@Service
public class HandleService {

    public static final Duration CHANGE_INTERVAL = Duration.ofDays(30);
    static final Duration RETIRED_RESERVATION = Duration.ofDays(30);
    static final Pattern FORMAT = Pattern.compile("^[a-z0-9.]{3,30}@qg$");
    /** Names that would let someone pose as the bank. */
    static final Set<String> RESERVED = Set.of("admin", "administrator", "support", "help", "helpdesk", "quantgen",
            "qg", "official", "security", "bank", "root", "system", "staff", "team", "service", "care", "refund",
            "refunds", "kyc", "verify", "billing", "payments");
    static final UserRateLimiter.Limit RESOLVE_LIMIT = new UserRateLimiter.Limit("payee-resolve", 30,
            Duration.ofMinutes(1));

    public record HandleDto(String handle, UUID accountId, Instant changeableAt) {
    }

    /** PRD 12.4 resolve response. */
    public record PayeeDto(String holderNameMasked, String handle, String accountNumberMasked) {
    }

    private record Row(String handle, UUID accountId, Instant changedAt) {
    }

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final UserService users;
    private final AuditService audit;
    private final UserRateLimiter rateLimiter;
    private final Clock clock;

    public HandleService(JdbcTemplate jdbc, AccountService accounts, UserService users, AuditService audit,
                         UserRateLimiter rateLimiter, Clock clock) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.users = users;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    /** Lower-cased and trimmed; null stays null. */
    public static String normalize(String handle) {
        return handle == null ? null : handle.trim().toLowerCase(Locale.ROOT);
    }

    @Transactional
    public HandleDto set(UUID userId, String rawHandle, UUID accountId) {
        String handle = normalize(rawHandle);
        if (handle == null || !FORMAT.matcher(handle).matches()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "handle",
                    "use 3-30 lower-case letters, digits or dots, followed by @qg");
        }
        String name = handle.substring(0, handle.length() - "@qg".length());
        if (name.startsWith(".") || name.endsWith(".") || name.contains("..")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "handle",
                    "must not start or end with a dot or contain two dots in a row");
        }
        if (RESERVED.contains(name)) {
            throw ApiException.fieldError(HttpStatus.CONFLICT, "handle", "is already taken");
        }
        AccountRef account = accounts.requireOwned(userId, accountId);
        if (!account.isEveryday()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountId", "pick a savings or checking account");
        }
        if (account.status() == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountId", "this account cannot receive money");
        }

        Instant now = clock.instant();
        // Lock the user's row so two concurrent changes by the same user can't both pass the 30-day check.
        jdbc.queryForList("SELECT id FROM users WHERE id = ? FOR UPDATE", UUID.class, userId);
        Optional<Row> current = find(userId);
        boolean changing = current.isEmpty() || !current.get().handle().equals(handle);

        if (changing) {
            if (current.isPresent()) {
                Instant changeableAt = current.get().changedAt().plus(CHANGE_INTERVAL);
                if (now.isBefore(changeableAt)) {
                    throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                            "A handle can be changed once every 30 days; next change possible after " + changeableAt);
                }
            }
            List<UUID> reservedFor = jdbc.queryForList(
                    "SELECT user_id FROM retired_handles WHERE handle = ? AND retired_at > ?", UUID.class,
                    handle, Timestamp.from(now.minus(RETIRED_RESERVATION)));
            if (!reservedFor.isEmpty() && !reservedFor.getFirst().equals(userId)) {
                throw ApiException.fieldError(HttpStatus.CONFLICT, "handle", "is already taken");
            }
            try {
                if (current.isPresent()) {
                    jdbc.update("UPDATE handles SET handle = ?, account_id = ?, changed_at = ? WHERE user_id = ?",
                            handle, accountId, Timestamp.from(now), userId);
                    jdbc.update("""
                            INSERT INTO retired_handles (handle, user_id, retired_at) VALUES (?, ?, ?)
                            ON CONFLICT (handle) DO UPDATE SET user_id = EXCLUDED.user_id, retired_at = EXCLUDED.retired_at
                            """, current.get().handle(), userId, Timestamp.from(now));
                } else {
                    jdbc.update("INSERT INTO handles (user_id, handle, account_id, changed_at) VALUES (?, ?, ?, ?)",
                            userId, handle, accountId, Timestamp.from(now));
                }
            } catch (DuplicateKeyException e) {
                throw ApiException.fieldError(HttpStatus.CONFLICT, "handle", "is already taken");
            }
            jdbc.update("DELETE FROM retired_handles WHERE handle = ?", handle);
            audit.record(userId, "HANDLE_SET", "USER", userId.toString(),
                    "handle=" + handle + (current.isPresent() ? " previous=" + current.get().handle() : "")
                            + " account=" + AccountNumbers.mask(account.accountNumber()));
        } else if (!current.get().accountId().equals(accountId)) {
            jdbc.update("UPDATE handles SET account_id = ? WHERE user_id = ?", accountId, userId);
            audit.record(userId, "HANDLE_ACCOUNT_CHANGED", "USER", userId.toString(),
                    "handle=" + handle + " account=" + AccountNumbers.mask(account.accountNumber()));
        }
        return get(userId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<HandleDto> get(UUID userId) {
        return find(userId).map(r -> new HandleDto(r.handle(), r.accountId(), r.changedAt().plus(CHANGE_INTERVAL)));
    }

    /** The user's handle, or null. */
    @Transactional(readOnly = true)
    public String handleOf(UUID userId) {
        return find(userId).map(Row::handle).orElse(null);
    }

    /** The user a handle belongs to, matched case-insensitively. */
    @Transactional(readOnly = true)
    public Optional<UUID> userFor(String handle) {
        String h = normalize(handle);
        if (h == null || !FORMAT.matcher(h).matches()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT user_id FROM handles WHERE handle = ?", UUID.class, h).stream().findFirst();
    }

    /** The account a handle pays into (any status; the caller decides), matched case-insensitively. */
    @Transactional(readOnly = true)
    public Optional<AccountRef> accountFor(String handle) {
        String h = normalize(handle);
        if (h == null || !FORMAT.matcher(h).matches()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT account_id FROM handles WHERE handle = ?", UUID.class, h).stream()
                .findFirst().flatMap(accounts::findById);
    }

    /** The account the user's handle pays into, if the user has a handle. */
    @Transactional(readOnly = true)
    public Optional<AccountRef> accountOf(UUID userId) {
        return find(userId).flatMap(r -> accounts.findById(r.accountId()));
    }

    /** F-PAY-6: who a handle or account number belongs to, masked. Exactly one of the two must be given. */
    @Transactional(readOnly = true)
    public PayeeDto resolve(UUID callerId, String handle, String accountNumber) {
        boolean byHandle = handle != null && !handle.isBlank();
        boolean byNumber = accountNumber != null && !accountNumber.isBlank();
        if (byHandle == byNumber) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, byHandle ? "accountNumber" : "handle",
                    "give exactly one of handle or accountNumber");
        }
        if (byNumber && !accountNumber.trim().matches("^[0-9]{12}$")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountNumber", "must be a 12-digit account number");
        }
        rateLimiter.acquire(RESOLVE_LIMIT, callerId);
        Optional<AccountRef> account = byHandle ? accountFor(handle) : accounts.findByNumber(accountNumber.trim());
        AccountRef found = account.filter(a -> a.status() != AccountStatus.CLOSED && a.isEveryday())
                .orElseThrow(() -> ApiException.notFound("Payee"));
        return new PayeeDto(maskName(users.getUser(found.userId()).fullName()), handleOf(found.userId()),
                AccountNumbers.mask(found.accountNumber()));
    }

    /** "Aarav Sharma" → "Aarav S."; a single name is shown as is. */
    public static String maskName(String fullName) {
        String[] parts = fullName == null ? new String[0] : fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "";
        }
        if (parts.length == 1) {
            return parts[0];
        }
        String last = parts[parts.length - 1];
        return parts[0] + " " + last.substring(0, last.offsetByCodePoints(0, 1)) + ".";
    }

    private Optional<Row> find(UUID userId) {
        return jdbc.query("SELECT handle, account_id, changed_at FROM handles WHERE user_id = ?",
                (rs, i) -> new Row(rs.getString("handle"), rs.getObject("account_id", UUID.class),
                        rs.getTimestamp("changed_at").toInstant()), userId).stream().findFirst();
    }
}
