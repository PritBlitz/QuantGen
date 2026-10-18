package com.quantgen.auth.security;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The facts the UI needs on {@code UserDto} beyond the user row: KYC status, step-up methods, payment handle. */
@Service
public class SecuritySummaryService {

    public record Summary(String kycStatus, boolean hasPasskey, boolean hasPin, String handle) {
        public static final Summary NONE = new Summary("NONE", false, false, null);
    }

    private final JdbcTemplate jdbc;

    public SecuritySummaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Summary of(UUID userId) {
        return jdbc.query("""
                SELECT COALESCE((SELECT status FROM kyc_profiles WHERE user_id = ?), 'NONE') AS kyc,
                       EXISTS (SELECT 1 FROM user_pins WHERE user_id = ?) AS has_pin,
                       EXISTS (SELECT 1 FROM passkeys WHERE user_id = ?) AS has_passkey,
                       (SELECT handle FROM handles WHERE user_id = ?) AS handle
                """, rs -> rs.next()
                ? new Summary(rs.getString("kyc"), rs.getBoolean("has_passkey"), rs.getBoolean("has_pin"),
                        rs.getString("handle"))
                : Summary.NONE, userId, userId, userId, userId);
    }
}
