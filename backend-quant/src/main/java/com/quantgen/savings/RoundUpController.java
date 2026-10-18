package com.quantgen.savings;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.security.AuthUser;
import com.quantgen.savings.RoundUpService.RoundUpDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.5 round-up settings (F-SAV-2), plus the nightly sweep job. */
@RestController
@Tag(name = "Savings")
public class RoundUpController {

    private static final Logger log = LoggerFactory.getLogger(RoundUpController.class);

    public record RoundUpRequest(@NotNull Boolean enabled, @NotNull Integer roundTo, UUID goalAccountId) {
    }

    private final RoundUpService roundUps;
    private final Clock clock;

    public RoundUpController(RoundUpService roundUps, Clock clock) {
        this.roundUps = roundUps;
        this.clock = clock;
    }

    @GetMapping("/api/v1/accounts/{id}/round-up")
    public RoundUpDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return roundUps.get(user.id(), id);
    }

    @PutMapping("/api/v1/accounts/{id}/round-up")
    @Operation(summary = "Round spends from this account up to the next 10/50/100 into one of your goals. "
            + "The round-ups are moved once a day (23:30 IST) as one transfer.")
    public RoundUpDto set(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                          @Valid @RequestBody RoundUpRequest request) {
        return roundUps.set(user.id(), id, request.enabled(), request.roundTo(), request.goalAccountId());
    }

    @Scheduled(cron = "${qg.savings.round-up-cron:0 30 23 * * *}", zone = "Asia/Kolkata")
    void nightlySweep() {
        try {
            int posted = roundUps.sweep(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
            if (posted > 0) {
                log.info("Swept round-ups into {} goal(s)", posted);
            }
        } catch (RuntimeException e) {
            log.error("Round-up sweep failed", e);
        }
    }
}
