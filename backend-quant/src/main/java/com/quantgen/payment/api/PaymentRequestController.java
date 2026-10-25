package com.quantgen.payment.api;

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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.auth.security.StepUpService;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.common.web.TextRules;
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.service.PaymentRequestService;
import com.quantgen.payment.service.PaymentRequestService.Direction;
import com.quantgen.payment.service.PaymentRequestService.PaymentRequestDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.4 payment requests (F-PAY-8). */
@RestController
@RequestMapping("/api/v1/payment-requests")
@Tag(name = "Payments")
public class PaymentRequestController {

    public enum Status { OPEN, PAID, DECLINED, EXPIRED, CANCELLED }

    public record CreateRequest(
            @NotBlank @Pattern(regexp = PaymentDtos.HANDLE_PATTERN, message = "must look like name@qg") String toHandle,
            @NotNull @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal amount,
            @Size(max = 140) @Pattern(regexp = TextRules.NO_CONTROL_CHARS,
                    message = "must not contain control characters") String note) {
    }

    public record PayRequest(@NotNull UUID fromAccountId) {
    }

    public record DeclineRequest(Boolean block) {
    }

    private final PaymentRequestService requests;
    private final IdempotencyService idempotency;

    public PaymentRequestController(PaymentRequestService requests, IdempotencyService idempotency) {
        this.requests = requests;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Ask a handle for money. You need a handle yourself; at most 20 open requests; expires in 7 days.")
    public ResponseEntity<PaymentRequestDto> create(@AuthenticationPrincipal AuthUser user,
                                                    @Valid @RequestBody CreateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(requests.create(user.id(), request.toHandle(), request.amount(), request.note()));
    }

    @GetMapping
    @Operation(summary = "Requests you received (INCOMING, default) or sent (OUTGOING), newest first")
    public PageDto<PaymentRequestDto> list(@AuthenticationPrincipal AuthUser user,
                                           @RequestParam(required = false) Direction direction,
                                           @RequestParam(required = false) Status status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return requests.list(user.id(), direction == null ? Direction.INCOMING : direction,
                status == null ? null : status.name(), PageDto.pageable(page, size));
    }

    @PostMapping("/{id}/pay")
    @Operation(summary = "Pay a request you received. Requires Idempotency-Key; transfer limits, cooling and step-up apply.")
    public ResponseEntity<TransferDto> pay(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                           @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                           @RequestHeader(value = StepUpService.HEADER, required = false) String stepUpToken,
                                           @Valid @RequestBody PayRequest request, HttpServletRequest http) {
        return Idempotent.created(requests.pay(user.id(), id, idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request.fromAccountId(),
                stepUpToken));
    }

    @GetMapping("/blocked")
    @Operation(summary = "People you blocked from sending you requests, newest first")
    public List<PaymentRequestService.BlockedDto> blocked(@AuthenticationPrincipal AuthUser user) {
        return requests.blocked(user.id());
    }

    @DeleteMapping("/blocked/{handle}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Unblock someone so they can ask you for money again")
    public void unblock(@AuthenticationPrincipal AuthUser user, @PathVariable String handle) {
        requests.unblock(user.id(), handle);
    }

    @PostMapping("/{id}/decline")
    @Operation(summary = "Decline a request you received (block: true also blocks the requester), "
            + "or cancel one you sent")
    public PaymentRequestDto decline(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                     @RequestBody(required = false) DeclineRequest request) {
        return requests.decline(user.id(), id, request != null && Boolean.TRUE.equals(request.block()));
    }
}
