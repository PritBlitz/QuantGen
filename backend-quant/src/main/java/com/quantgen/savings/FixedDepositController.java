package com.quantgen.savings;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.savings.FixedDepositService.FixedDepositDto;
import com.quantgen.savings.FixedDepositService.OnMaturity;
import com.quantgen.savings.FixedDepositService.RateDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.5 fixed deposits (F-SAV-3). */
@RestController
@RequestMapping("/api/v1/fixed-deposits")
@Tag(name = "Savings")
public class FixedDepositController {

    public record BookRequest(@NotNull UUID fromAccountId,
                              @NotNull @MoneyAmount(min = "1000.00", max = "1000000.00") BigDecimal amount,
                              @NotNull Integer tenureDays,
                              @NotNull OnMaturity onMaturity) {
    }

    private final FixedDepositService deposits;
    private final IdempotencyService idempotency;

    public FixedDepositController(FixedDepositService deposits, IdempotencyService idempotency) {
        this.deposits = deposits;
        this.idempotency = idempotency;
    }

    @GetMapping("/rates")
    @Operation(summary = "The published rate slabs (simple interest, % p.a.)")
    public List<RateDto> rates() {
        return deposits.rates();
    }

    @PostMapping
    @Operation(summary = "Book a fixed deposit from a savings or checking account (₹1,000–₹10,00,000, 7–1825 days). "
            + "Requires Idempotency-Key.")
    public ResponseEntity<FixedDepositDto> book(@AuthenticationPrincipal AuthUser user,
                                                @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                @Valid @RequestBody BookRequest request, HttpServletRequest http) {
        return Idempotent.created(deposits.book(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request.fromAccountId(),
                request.amount(), request.tenureDays(), request.onMaturity()));
    }

    @GetMapping
    @Operation(summary = "Your fixed deposits, newest first (a renewal is a new row)")
    public List<FixedDepositDto> list(@AuthenticationPrincipal AuthUser user) {
        return deposits.list(user.id());
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close early: pays the slab rate for the days held minus 1%. Requires Idempotency-Key and "
            + "step-up FD_CLOSE.")
    public ResponseEntity<FixedDepositDto> close(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                                 @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                 @RequestHeader(value = StepUpService.HEADER, required = false)
                                                 String stepUpToken, HttpServletRequest http) {
        return Idempotent.ok(deposits.close(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), null), id, stepUpToken));
    }
}
