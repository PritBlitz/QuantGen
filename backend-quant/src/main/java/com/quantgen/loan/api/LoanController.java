package com.quantgen.loan.api;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.common.idempotency.Idempotent;
import com.quantgen.common.security.AuthUser;
import com.quantgen.loan.api.LoanDtos.ApplyRequest;
import com.quantgen.loan.api.LoanDtos.LoanDetailDto;
import com.quantgen.loan.api.LoanDtos.LoanDto;
import com.quantgen.loan.api.LoanDtos.QuoteDto;
import com.quantgen.loan.api.LoanDtos.QuoteRequest;
import com.quantgen.loan.api.LoanDtos.RepayRequest;
import com.quantgen.loan.api.LoanDtos.RepaymentDto;
import com.quantgen.loan.service.LoanService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.6 loans. */
@RestController
@RequestMapping("/api/v1/loans")
@Tag(name = "Loans")
public class LoanController {

    private final LoanService loans;
    private final IdempotencyService idempotency;

    public LoanController(LoanService loans, IdempotencyService idempotency) {
        this.loans = loans;
        this.idempotency = idempotency;
    }

    @PostMapping("/quote")
    @Operation(summary = "EMI and eligibility for a prospective loan (12% p.a.; EMIs ≤ 50% and amount ≤ 20× monthly income)")
    public QuoteDto quote(@AuthenticationPrincipal AuthUser user, @Valid @RequestBody QuoteRequest request) {
        return loans.quote(user.id(), request);
    }

    @PostMapping
    @Operation(summary = "Apply for a loan. LOAN_NOT_ELIGIBLE if the eligibility rules fail.")
    public ResponseEntity<LoanDto> apply(@AuthenticationPrincipal AuthUser user,
                                         @Valid @RequestBody ApplyRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(loans.apply(user.id(), request));
    }

    @GetMapping
    public List<LoanDto> list(@AuthenticationPrincipal AuthUser user) {
        return loans.list(user.id());
    }

    @GetMapping("/{id}")
    public LoanDetailDto get(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id) {
        return loans.get(user.id(), id);
    }

    @PostMapping("/{id}/repay")
    @Operation(summary = "Repay the next due EMI, or a custom amount applied to the oldest dues first. Requires Idempotency-Key.")
    public ResponseEntity<RepaymentDto> repay(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                              @RequestHeader(Idempotent.HEADER) UUID idempotencyKey,
                                              @Valid @RequestBody RepayRequest request, HttpServletRequest http) {
        return Idempotent.created(loans.repay(user.id(), id, idempotencyKey,
                idempotency.fingerprint(http.getMethod(), http.getRequestURI(), request), request));
    }
}
