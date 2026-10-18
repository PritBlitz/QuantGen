package com.quantgen.payment.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.auth.security.StepUpAction;
import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Money;
import com.quantgen.payment.domain.TransferRepository;

/**
 * F-SEC-7: per-transfer and daily limits to other people. The platform maximums are fixed; a customer may
 * choose lower values, and any change needs a step-up (SECURITY_SETTINGS), so a stolen session cannot quietly
 * raise them. A limit equal to the platform maximum is the default.
 */
@Service
public class LimitsService {

    public static final BigDecimal PER_TRANSFER_MAX = new BigDecimal("200000.00");
    public static final BigDecimal DAILY_MAX = new BigDecimal("500000.00");

    private final JdbcTemplate jdbc;
    private final TransferRepository transfers;
    private final StepUpService stepUp;
    private final AuditService audit;
    private final Clock clock;

    public LimitsService(JdbcTemplate jdbc, TransferRepository transfers, StepUpService stepUp, AuditService audit,
                         Clock clock) {
        this.jdbc = jdbc;
        this.transfers = transfers;
        this.stepUp = stepUp;
        this.audit = audit;
        this.clock = clock;
    }

    /** PRD 12.3 LimitsDto. */
    public record LimitsDto(BigDecimal perTransfer, BigDecimal daily, BigDecimal perTransferMax, BigDecimal dailyMax,
                            BigDecimal usedToday) {
    }

    public record Effective(BigDecimal perTransfer, BigDecimal daily) {
    }

    @Transactional(readOnly = true)
    public Effective effective(UUID userId) {
        List<Effective> rows = jdbc.query("SELECT per_transfer, daily FROM user_limits WHERE user_id = ?",
                (rs, i) -> new Effective(
                        rs.getBigDecimal("per_transfer") == null ? PER_TRANSFER_MAX : rs.getBigDecimal("per_transfer"),
                        rs.getBigDecimal("daily") == null ? DAILY_MAX : rs.getBigDecimal("daily")), userId);
        return rows.isEmpty() ? new Effective(PER_TRANSFER_MAX, DAILY_MAX) : rows.getFirst();
    }

    @Transactional(readOnly = true)
    public LimitsDto get(UUID userId) {
        Effective e = effective(userId);
        return new LimitsDto(e.perTransfer(), e.daily(), PER_TRANSFER_MAX, DAILY_MAX, usedToday(userId));
    }

    @Transactional
    public LimitsDto set(UUID userId, String stepUpToken, BigDecimal perTransfer, BigDecimal daily) {
        stepUp.require(userId, StepUpAction.SECURITY_SETTINGS, stepUpToken);
        if (perTransfer == null && daily == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "perTransfer", "provide perTransfer or daily");
        }
        Effective current = effective(userId);
        BigDecimal newPer = perTransfer == null ? current.perTransfer() : Money.normalize(perTransfer).setScale(2);
        BigDecimal newDaily = daily == null ? current.daily() : Money.normalize(daily).setScale(2);
        if (newPer.compareTo(PER_TRANSFER_MAX) > 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "perTransfer",
                    "cannot exceed the platform maximum of " + Money.toJson(PER_TRANSFER_MAX));
        }
        if (newDaily.compareTo(DAILY_MAX) > 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "daily",
                    "cannot exceed the platform maximum of " + Money.toJson(DAILY_MAX));
        }
        if (newPer.compareTo(newDaily) > 0) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "perTransfer", "cannot exceed the daily limit");
        }
        jdbc.update("""
                INSERT INTO user_limits (user_id, per_transfer, daily) VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET per_transfer = EXCLUDED.per_transfer, daily = EXCLUDED.daily,
                                                    updated_at = now()
                """, userId, newPer, newDaily);
        audit.record(userId, "LIMITS_CHANGED", "USER", userId.toString(),
                "perTransfer=" + Money.toJson(newPer) + " daily=" + Money.toJson(newDaily));
        return get(userId);
    }

    @Transactional(readOnly = true)
    public BigDecimal usedToday(UUID userId) {
        Instant startOfDay = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return transfers.sumOutgoingSince(userId, startOfDay);
    }
}
