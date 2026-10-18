package com.quantgen.payment.api;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.payment.service.TopupService;
import com.quantgen.payment.service.TopupService.TopupDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 7.9 gateway top-ups (Razorpay test mode, F-PAY-10). */
@RestController
@Tag(name = "Payments")
public class TopupController {

    public record CreateTopupRequest(@NotNull @MoneyAmount(min = "1.00", max = "100000.00") BigDecimal amount) {
    }

    public record VerifyRequest(@NotBlank @Size(max = 40) String gatewayPaymentId,
                                @NotBlank @Size(max = 128) String gatewaySignature) {

        @Override
        public String toString() {
            return "VerifyRequest[gatewayPaymentId=" + gatewayPaymentId + "]";
        }
    }

    private final TopupService topups;
    private final IdempotencyService idempotency;

    public TopupController(TopupService topups, IdempotencyService idempotency) {
        this.topups = topups;
        this.idempotency = idempotency;
    }

    @PostMapping("/api/v1/accounts/{id}/topups")
    @Operation(summary = "Create a Razorpay order to add 1.00-100000.00 INR. Requires Idempotency-Key. No money moves "
            + "until verify or the webhook confirms the payment.")
    public ResponseEntity<TopupDto> create(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                           @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                           @Valid @RequestBody CreateTopupRequest request, HttpServletRequest http) {
        return Idempotent.created(topups.create(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), id, request.amount()));
    }

    @PostMapping("/api/v1/topups/{id}/verify")
    @Operation(summary = "Confirm a Checkout payment: checks the signature and that Razorpay reports it captured for "
            + "the same amount, then credits once. An already credited top-up returns 200 unchanged.")
    public TopupDto verify(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                           @Valid @RequestBody VerifyRequest request) {
        return topups.verify(user.id(), id, request.gatewayPaymentId(), request.gatewaySignature());
    }

    @GetMapping("/api/v1/topups/{id}")
    public TopupDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return topups.get(user.id(), id);
    }

    @GetMapping("/api/v1/accounts/{id}/topups")
    public PageDto<TopupDto> list(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        return topups.list(user.id(), id, PageDto.pageable(page, size));
    }

    @PostMapping("/api/v1/webhooks/razorpay")
    @Operation(summary = "Razorpay webhook (no JWT; HMAC-signed). 400 on a bad signature; repeats are acknowledged.")
    public ResponseEntity<Void> webhook(@RequestBody byte[] body,
                                        @RequestHeader(value = "X-Razorpay-Signature", required = false) String signature,
                                        @RequestHeader(value = "X-Razorpay-Event-Id", required = false) String eventId) {
        topups.webhook(body, signature, eventId);
        return ResponseEntity.ok().build();
    }
}
