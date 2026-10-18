package com.quantgen.payment.api;

import java.util.List;
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
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.payment.service.BillService;
import com.quantgen.payment.service.BillService.BillDto;
import com.quantgen.payment.service.BillService.BillPaymentDto;
import com.quantgen.payment.service.BillService.BillerDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.4 billers and bill payments (F-PAY-11). */
@RestController
@Tag(name = "Payments")
public class BillController {

    public enum Category { ELECTRICITY, MOBILE, BROADBAND, DTH, GAS, WATER, CREDIT_CARD, INSURANCE }

    public record FetchRequest(@NotBlank @Size(max = 20) String consumerNumber) {
    }

    public record PayRequest(@NotNull UUID billId, @NotNull UUID fromAccountId) {
    }

    private final BillService bills;
    private final IdempotencyService idempotency;

    public BillController(BillService bills, IdempotencyService idempotency) {
        this.bills = bills;
        this.idempotency = idempotency;
    }

    @GetMapping("/api/v1/billers")
    @Operation(summary = "Simulated billers, optionally of one category")
    public List<BillerDto> billers(@RequestParam(required = false) Category category) {
        return bills.billers(category == null ? null : category.name());
    }

    @PostMapping("/api/v1/billers/{id}/fetch")
    @Operation(summary = "Fetch this month's (simulated) bill for a consumer number. The bill can be paid for 24 hours.")
    public BillDto fetch(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                         @Valid @RequestBody FetchRequest request) {
        return bills.fetch(user.id(), id, request.consumerNumber());
    }

    @PostMapping("/api/v1/bill-payments")
    @Operation(summary = "Pay a fetched bill. Requires Idempotency-Key; a bill can be paid once.")
    public ResponseEntity<BillPaymentDto> pay(@AuthenticationPrincipal AuthUser user,
                                              @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                              @Valid @RequestBody PayRequest request, HttpServletRequest http) {
        return Idempotent.created(bills.pay(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request.billId(),
                request.fromAccountId()));
    }

    @GetMapping("/api/v1/bill-payments")
    @Operation(summary = "Your bill payments (receipts), newest first")
    public PageDto<BillPaymentDto> list(@AuthenticationPrincipal AuthUser user,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return bills.list(user.id(), PageDto.pageable(page, size));
    }
}
