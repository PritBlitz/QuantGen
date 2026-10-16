package com.quantgen.admin.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.security.AuthUser;
import com.quantgen.common.web.PageDto;
import com.quantgen.loan.api.LoanDtos.AdminLoanDto;
import com.quantgen.loan.api.LoanDtos.ApproveRequest;
import com.quantgen.loan.api.LoanDtos.LoanDto;
import com.quantgen.loan.api.LoanDtos.RejectRequest;
import com.quantgen.loan.domain.LoanStatus;
import com.quantgen.loan.service.LoanService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD §7.7 loan queue (role ADMIN, enforced for /api/v1/admin/** in SecurityConfig). */
@RestController
@RequestMapping("/api/v1/admin/loans")
@Tag(name = "Admin")
public class AdminLoanController {

    private final LoanService loans;

    public AdminLoanController(LoanService loans) {
        this.loans = loans;
    }

    @GetMapping
    @Operation(summary = "Loans, newest first, optionally filtered by status")
    public PageDto<AdminLoanDto> list(@RequestParam(required = false) LoanStatus status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return loans.adminList(status, PageDto.pageable(page, size));
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "Approve and disburse a PENDING loan (409 INVALID_STATE otherwise)")
    public LoanDto approve(@AuthenticationPrincipal AuthUser admin, @PathVariable UUID id,
                           @Valid @RequestBody(required = false) ApproveRequest request) {
        return loans.approve(admin.id(), id, request == null ? null : request.reason());
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "Reject a PENDING loan with a reason (409 INVALID_STATE otherwise)")
    public LoanDto reject(@AuthenticationPrincipal AuthUser admin, @PathVariable UUID id,
                          @Valid @RequestBody RejectRequest request) {
        return loans.reject(admin.id(), id, request.reason());
    }
}
