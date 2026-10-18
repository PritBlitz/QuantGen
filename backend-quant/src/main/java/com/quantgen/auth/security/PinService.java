package com.quantgen.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.crypto.FieldCrypto;
import com.quantgen.common.error.ApiException;

/**
 * F-SEC-3: a 6-digit transaction PIN, the step-up fallback when no passkey is available.
 *
 * <p>Storage: BCrypt over {@code HMAC(index-key, userId:pin)}, so a leaked table is useless without the server
 * key (a bare 6-digit PIN would fall to offline guessing in seconds). Verification locks the row, so parallel
 * guesses cannot outrun the counter: 5 wrong tries lock the PIN for 30 minutes. The PIN is never logged.
 */
@Service
public class PinService {

    static final int MAX_FAILED = 5;
    static final Duration LOCK = Duration.ofMinutes(30);
    private static final Set<String> BANNED = Set.of("123456", "654321", "012345", "123123", "121212");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final FieldCrypto crypto;
    private final AuditService audit;
    private final Clock clock;

    public PinService(JdbcTemplate jdbc, PasswordEncoder encoder, FieldCrypto crypto, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public boolean hasPin(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM user_pins WHERE user_id = ?)",
                Boolean.class, userId));
    }

    /** Creates or replaces the PIN and clears any lockout. The caller has already authorised the change. */
    @Transactional
    public void setPin(UUID userId, String pin) {
        validateFormat(pin);
        String hash = encoder.encode(digest(userId, pin));
        jdbc.update("""
                INSERT INTO user_pins (user_id, pin_hash) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE
                   SET pin_hash = EXCLUDED.pin_hash, failed_count = 0, locked_until = NULL, updated_at = now()
                """, userId, hash);
        audit.record(userId, "PIN_SET", "USER", userId.toString(), null);
    }

    /**
     * Checks the PIN. Wrong guesses are counted and committed even though an exception follows. Errors:
     * 423 PIN_LOCKED while locked; 400 on a wrong PIN; 400 if the user has no PIN.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void verify(UUID userId, String pin) {
        List<Row> rows = jdbc.query("SELECT pin_hash, failed_count, locked_until FROM user_pins WHERE user_id = ? "
                        + "FOR UPDATE",
                (rs, i) -> new Row(rs.getString("pin_hash"), rs.getInt("failed_count"),
                        rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant()),
                userId);
        if (rows.isEmpty()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin", "no PIN is set");
        }
        Row row = rows.getFirst();
        Instant now = clock.instant();
        if (row.lockedUntil() != null && row.lockedUntil().isAfter(now)) {
            throw ApiException.pinLocked(Duration.between(now, row.lockedUntil()).toSeconds());
        }
        // Compare even when the format is wrong, so response time does not reveal anything.
        boolean ok = pin != null && encoder.matches(digest(userId, pin), row.hash());
        if (ok) {
            if (row.failedCount() > 0 || row.lockedUntil() != null) {
                jdbc.update("UPDATE user_pins SET failed_count = 0, locked_until = NULL WHERE user_id = ?", userId);
            }
            return;
        }
        int failed = (row.lockedUntil() != null ? 0 : row.failedCount()) + 1; // an expired lock starts a fresh count
        if (failed >= MAX_FAILED) {
            Instant until = now.plus(LOCK);
            jdbc.update("UPDATE user_pins SET failed_count = 0, locked_until = ? WHERE user_id = ?",
                    java.sql.Timestamp.from(until), userId);
            audit.record(userId, "PIN_LOCKED", "USER", userId.toString(), "too many wrong PINs");
            throw ApiException.pinLocked(LOCK.toSeconds());
        }
        jdbc.update("UPDATE user_pins SET failed_count = ?, locked_until = NULL WHERE user_id = ?", failed, userId);
        audit.record(userId, "PIN_FAILED", "USER", userId.toString(), null);
        throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin",
                "is incorrect (" + (MAX_FAILED - failed) + " attempts left)");
    }

    static void validateFormat(String pin) {
        if (pin == null || !pin.matches("[0-9]{6}")) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin", "must be exactly 6 digits");
        }
        boolean allSame = pin.chars().distinct().count() == 1;
        if (allSame || BANNED.contains(pin)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pin", "is too easy to guess");
        }
    }

    private String digest(UUID userId, String pin) {
        return crypto.blindIndex(userId + ":" + pin, "user.pin");
    }

    private record Row(String hash, int failedCount, Instant lockedUntil) {
    }
}
