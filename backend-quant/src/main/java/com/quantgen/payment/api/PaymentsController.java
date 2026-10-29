package com.quantgen.payment.api;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.payment.service.PaymentsViewService;
import com.quantgen.payment.service.PaymentsViewService.PaymentDto;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §12.4 "Payments view": the caller's own payments with receipt numbers. */
@RestController
@Tag(name = "Payments")
public class PaymentsController {

    private final PaymentsViewService payments;

    public PaymentsController(PaymentsViewService payments) {
        this.payments = payments;
    }

    @GetMapping("/api/v1/payments")
    @Operation(summary = "Your payments across transfers, bills, top-ups and cards, newest first. from/to are IST dates.")
    public PageDto<PaymentDto> list(@AuthenticationPrincipal AuthUser user,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                    @RequestParam(required = false) String kind,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String q,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return payments.list(user.id(), from, to, kind, status, q, PageDto.pageable(page, size));
    }

    @GetMapping("/api/v1/payments/{receiptNo}")
    @Operation(summary = "Your side of one payment by receipt number; someone else's is 404.")
    public PaymentDto get(@AuthenticationPrincipal AuthUser user, @PathVariable String receiptNo) {
        return payments.get(user.id(), receiptNo);
    }
}
