package com.quantgen.payment.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.payment.api.PaymentDtos.TransferDto;
import com.quantgen.payment.api.PaymentDtos.TransferRequest;
import com.quantgen.payment.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.4 transfers. */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Payments")
public class TransferController {

    private final TransferService transfers;
    private final IdempotencyService idempotency;

    public TransferController(TransferService transfers, IdempotencyService idempotency) {
        this.transfers = transfers;
        this.idempotency = idempotency;
    }

    @PostMapping
    @Operation(summary = "Transfer to any account number. Requires Idempotency-Key. "
            + "Limits: 1.00-200000.00 per transfer, 500000.00 per IST day to other people.")
    public ResponseEntity<TransferDto> transfer(@AuthenticationPrincipal AuthUser user,
                                                @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                                @Valid @RequestBody TransferRequest request,
                                                HttpServletRequest http) {
        return Idempotent.created(transfers.transfer(user.id(), idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request));
    }

    @GetMapping
    @Operation(summary = "Transfers initiated by the caller, newest first")
    public PageDto<TransferDto> list(@AuthenticationPrincipal AuthUser user,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return transfers.list(user.id(), PageDto.pageable(page, size));
    }
}
