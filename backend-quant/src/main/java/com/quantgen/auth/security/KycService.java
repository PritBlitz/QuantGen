package com.quantgen.auth.security;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.crypto.FieldCrypto;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

/**
 * F-SEC-6 / D-032: the KYC profile. PAN and address are stored as AES-GCM ciphertext; PAN uniqueness uses an
 * HMAC blind index. Only the masked PAN ever leaves this class, and neither PAN nor address is logged or
 * audited. No Aadhaar numbers are accepted. There is no government verification in the sandbox (non-goal):
 * a well-formed submission is marked VERIFIED immediately.
 */
@Service
public class KycService {

    public static final BigDecimal LOAN_KYC_THRESHOLD = new BigDecimal("200000.00");

    private static final Pattern PAN = Pattern.compile("[A-Z]{5}[0-9]{4}[A-Z]");
    private static final String PAN_CTX = "kyc.pan";
    private static final String ADDRESS_CTX = "kyc.address";

    /** PRD 12.14 KycDto. */
    public record KycDto(String status, String panMasked, LocalDate dateOfBirth, String address, Instant verifiedAt) {
    }

    private final JdbcTemplate jdbc;
    private final FieldCrypto crypto;
    private final AuditService audit;
    private final Clock clock;
    private final StepUpService stepUp;

    public KycService(JdbcTemplate jdbc, FieldCrypto crypto, AuditService audit, Clock clock, StepUpService stepUp) {
        this.stepUp = stepUp;
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public KycDto get(UUID userId) {
        List<KycDto> rows = jdbc.query("""
                SELECT status, pan_last4, date_of_birth, address_enc, verified_at FROM kyc_profiles WHERE user_id = ?
                """, (rs, i) -> new KycDto(rs.getString("status"),
                rs.getString("pan_last4") == null ? null : "XXXXXX" + rs.getString("pan_last4"),
                rs.getDate("date_of_birth") == null ? null : rs.getDate("date_of_birth").toLocalDate(),
                rs.getString("address_enc") == null ? null : crypto.decrypt(rs.getString("address_enc"), ADDRESS_CTX),
                rs.getTimestamp("verified_at") == null ? null : rs.getTimestamp("verified_at").toInstant()), userId);
        return rows.isEmpty() ? new KycDto("NONE", null, null, null, null) : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public boolean isVerified(UUID userId) {
        return "VERIFIED".equals(get(userId).status());
    }

    /** 403 KYC_REQUIRED unless the user's KYC is VERIFIED. */
    @Transactional(readOnly = true)
    public void requireVerified(UUID userId) {
        if (!isVerified(userId)) {
            throw new ApiException(ErrorCode.KYC_REQUIRED);
        }
    }

    @Transactional
    public KycDto submit(UUID userId, String stepUpToken, String rawPan, LocalDate dateOfBirth, String rawAddress) {
        // Replacing details that are already on file is a security-settings change; the first submission is not.
        if (Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM kyc_profiles WHERE user_id = ? AND pan_enc IS NOT NULL)", Boolean.class, userId))) {
            stepUp.require(userId, StepUpAction.SECURITY_SETTINGS, stepUpToken);
        }
        String pan = rawPan == null ? "" : rawPan.trim().toUpperCase(Locale.ROOT);
        if (!PAN.matcher(pan).matches()) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "pan", "must look like ABCDE1234F");
        }
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (dateOfBirth == null || !dateOfBirth.isBefore(today)
                || Period.between(dateOfBirth, today).getYears() < 18
                || Period.between(dateOfBirth, today).getYears() > 120) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "dateOfBirth", "must be a date at least 18 years ago");
        }
        String address = rawAddress == null ? "" : rawAddress.trim();
        if (address.length() < 10 || address.length() > 300 || address.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "address", "must be 10-300 characters");
        }
        String index = crypto.blindIndex(pan, PAN_CTX);
        try {
            jdbc.update("""
                    INSERT INTO kyc_profiles (user_id, status, pan_enc, pan_idx, pan_last4, date_of_birth, address_enc,
                                              verified_at)
                    VALUES (?, 'VERIFIED', ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE
                       SET status = 'VERIFIED', pan_enc = EXCLUDED.pan_enc, pan_idx = EXCLUDED.pan_idx,
                           pan_last4 = EXCLUDED.pan_last4, date_of_birth = EXCLUDED.date_of_birth,
                           address_enc = EXCLUDED.address_enc, verified_at = now(), updated_at = now()
                    """, userId, crypto.encrypt(pan, PAN_CTX), index, pan.substring(6), java.sql.Date.valueOf(dateOfBirth),
                    crypto.encrypt(address, ADDRESS_CTX), java.sql.Timestamp.from(clock.instant()));
        } catch (DuplicateKeyException e) {
            // The same PAN on another account. Say so without saying whose.
            throw ApiException.fieldError(HttpStatus.CONFLICT, "pan", "is already linked to another account");
        }
        audit.record(userId, "KYC_SUBMITTED", "USER", userId.toString(), null); // no PAN, no address
        return get(userId);
    }
}
