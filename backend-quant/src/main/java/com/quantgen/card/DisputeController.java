package com.quantgen.card;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.card.DisputeService.AdminCardDisputeDto;
import com.quantgen.card.DisputeService.CardDisputeDto;
import com.quantgen.card.DisputeService.CardPaymentDto;
import com.quantgen.card.DisputeService.Outcome;
import com.quantgen.card.DisputeService.Reason;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.common.web.TextRules;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.11 F-CARD-4 card disputes: the cardholder opens one, an admin refunds or rejects it. */
@RestController
@Tag(name = "Cards")
public class DisputeController {

    public record DisputeRequest(@NotNull Reason reason,
                                 @MoneyAmount(min = "0.01", max = "200000.00") BigDecimal amount,
                                 @Size(max = 280) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                                         message = TextRules.NO_CONTROL_CHARS_MESSAGE) String note) {
    }

    public record ResolveRequest(@NotNull Outcome outcome,
                                 @NotBlank @Size(max = 280) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                                         message = TextRules.NO_CONTROL_CHARS_MESSAGE) String reason) {
    }

    private final DisputeService disputes;
    private final IdempotencyService idempotency;

    public DisputeController(DisputeService disputes, IdempotencyService idempotency) {
        this.disputes = disputes;
        this.idempotency = idempotency;
    }

    @GetMapping("/api/v1/cards/{id}/payments")
    @Operation(summary = "Captured and refunded payments on your card, newest first, with what can still be disputed")
    public PageDto<CardPaymentDto> payments(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                            @RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return disputes.payments(user.id(), id, PageDto.pageable(page, size));
    }

    @PostMapping("/api/v1/cards/{id}/payments/{authId}/dispute")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Dispute a captured payment within 60 days (all of what's disputable by default)")
    public CardDisputeDto open(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id, @PathVariable UUID authId,
                               @Valid @RequestBody DisputeRequest request) {
        return disputes.open(user.id(), id, authId, request.reason(), request.amount(), request.note());
    }

    @GetMapping("/api/v1/card-disputes")
    @Operation(summary = "Your card disputes, newest first")
    public List<CardDisputeDto> list(@AuthenticationPrincipal AuthUser user) {
        return disputes.list(user.id());
    }

    @GetMapping("/api/v1/admin/card-disputes")
    @Operation(summary = "Card disputes for review (oldest first), optionally by status")
    public PageDto<AdminCardDisputeDto> adminList(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Integer page,
                                                  @RequestParam(required = false) Integer size) {
        return disputes.adminList(status, PageDto.pageable(page, size));
    }

    @PostMapping("/api/v1/admin/card-disputes/{id}/resolve")
    @Operation(summary = "Refund (REFUND entry to the customer) or reject an open dispute. Requires Idempotency-Key.")
    public ResponseEntity<CardDisputeDto> resolve(@AuthenticationPrincipal AuthUser admin, @PathVariable UUID id,
                                                  @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                  @Valid @RequestBody ResolveRequest request, HttpServletRequest http) {
        return Idempotent.ok(disputes.resolve(admin.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), id, request.outcome(),
                request.reason()));
    }
}
