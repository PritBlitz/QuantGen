package com.quantgen.payment.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.quantgen.common.web.TextRules;
import com.quantgen.payment.service.ScheduledTransferService;
import com.quantgen.payment.service.ScheduledTransferService.CreateCommand;
import com.quantgen.payment.service.ScheduledTransferService.Frequency;
import com.quantgen.payment.service.ScheduledTransferService.ScheduledTransferDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.4 scheduled and recurring transfers (F-PAY-9). */
@RestController
@RequestMapping("/api/v1/scheduled-transfers")
@Tag(name = "Payments")
public class ScheduledTransferController {

    public record CreateRequest(
            @NotNull UUID fromAccountId,
            @Pattern(regexp = "^[0-9]{12}$", message = "must be a 12-digit account number") String toAccountNumber,
            @Pattern(regexp = PaymentDtos.HANDLE_PATTERN, message = "must look like name@qg") String toHandle,
            @NotNull @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal amount,
            @Size(max = 140) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                    message = "must not contain control characters") String note,
            @NotNull LocalDate startDate,
            @NotNull Frequency frequency,
            LocalDate endDate,
            @Min(1) @Max(ScheduledTransferService.MAX_COUNT) Integer count) {
    }

    private final ScheduledTransferService scheduled;
    private final IdempotencyService idempotency;

    public ScheduledTransferController(ScheduledTransferService scheduled, IdempotencyService idempotency) {
        this.scheduled = scheduled;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Schedule a one-off (ONCE) or recurring (WEEKLY / MONTHLY, day 1-28) transfer. Requires "
            + "Idempotency-Key; above 50000.00 to someone else it needs step-up TRANSFER now, because runs happen "
            + "unattended at 07:00 IST.")
    public ResponseEntity<ScheduledTransferDto> create(@AuthenticationPrincipal AuthUser user,
                                                       @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                       @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                                                       @Valid @RequestBody CreateRequest request,
                                                       HttpServletRequest http) {
        CreateCommand cmd = new CreateCommand(request.fromAccountId(), request.toAccountNumber(), request.toHandle(),
                request.amount(), request.note(), request.startDate(), request.frequency(), request.endDate(),
                request.count());
        return Idempotent.created(scheduled.create(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), cmd, stepUpToken));
    }

    @GetMapping
    @Operation(summary = "Your scheduled transfers, newest first (including completed and cancelled ones)")
    public List<ScheduledTransferDto> list(@AuthenticationPrincipal AuthUser user) {
        return scheduled.list(user.id());
    }

    @PostMapping("/{id}/pause")
    public ScheduledTransferDto pause(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return scheduled.pause(user.id(), id);
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "Resume a paused transfer. Occurrences that fell inside the pause are skipped, not paid late.")
    public ScheduledTransferDto resume(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return scheduled.resume(user.id(), id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        scheduled.cancel(user.id(), id);
        return ResponseEntity.noContent().build();
    }
}
