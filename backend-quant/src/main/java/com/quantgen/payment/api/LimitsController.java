package com.quantgen.payment.api;

import java.math.BigDecimal;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.payment.service.LimitsService;
import com.quantgen.payment.service.LimitsService.LimitsDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** PRD 12.3 limits. */
@RestController
@RequestMapping("/api/v1/users/me/limits")
@Tag(name = "Security")
public class LimitsController {

    public record SetLimitsRequest(
            @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal perTransfer,
            @MoneyAmount(min = "1.00", max = "500000.00") BigDecimal daily) {
    }

    private final LimitsService limits;

    public LimitsController(LimitsService limits) {
        this.limits = limits;
    }

    @GetMapping
    public LimitsDto get(@AuthenticationPrincipal AuthUser user) {
        return limits.get(user.id());
    }

    @PutMapping
    @Operation(summary = "Lower (or restore up to the platform maximum) your transfer limits. Needs step-up SECURITY_SETTINGS.")
    public LimitsDto set(@AuthenticationPrincipal AuthUser user,
                         @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                         @Valid @RequestBody SetLimitsRequest request) {
        return limits.set(user.id(), stepUpToken, request.perTransfer(), request.daily());
    }
}
