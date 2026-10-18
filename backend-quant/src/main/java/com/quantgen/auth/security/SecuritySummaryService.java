package com.quantgen.auth.security;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The security facts the UI needs on {@code UserDto}: KYC status and which step-up methods exist. */
@Service
public class SecuritySummaryService {

    public record Summary(String kycStatus, boolean hasPasskey, boolean hasPin) {
        public static final Summary NONE = new Summary("NONE", false, false);
    }

    private final JdbcTemplate jdbc;

    public SecuritySummaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Summary of(UUID userId) {
        return jdbc.query("""
                SELECT COALESCE((SELECT status FROM kyc_profiles WHERE user_id = ?), 'NONE') AS kyc,
                       EXISTS (SELECT 1 FROM user_pins WHERE user_id = ?) AS has_pin
                """, rs -> rs.next()
                ? new Summary(rs.getString("kyc"), false, rs.getBoolean("has_pin"))
                : Summary.NONE, userId, userId);
    }
}
