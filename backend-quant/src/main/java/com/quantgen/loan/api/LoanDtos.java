package com.quantgen.loan.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.web.TextRules;
import com.quantgen.loan.domain.InstallmentStatus;
import com.quantgen.loan.domain.LoanStatus;

/** PRD §7.6 (and the loan rows of §7.7) request and response bodies. */
public final class LoanDtos {

    private LoanDtos() {
    }

    /** F-LOAN-1 bounds. Monthly income is capped only to keep the arithmetic sane. */
    public record QuoteRequest(
            @NotNull @MoneyAmount(min = "10000.00", max = "1000000.00") BigDecimal amount,
            @NotNull @Min(6) @Max(60) Integer tenureMonths,
            @NotNull @MoneyAmount(min = "1.00", max = "100000000.00") BigDecimal monthlyIncome) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QuoteDto(BigDecimal emi, BigDecimal totalInterest, BigDecimal totalPayable, boolean eligible,
                           String reason) {
    }

    public record ApplyRequest(
            @NotNull @MoneyAmount(min = "10000.00", max = "1000000.00") BigDecimal amount,
            @NotNull @Min(6) @Max(60) Integer tenureMonths,
            @NotNull @MoneyAmount(min = "1.00", max = "100000000.00") BigDecimal monthlyIncome,
            @NotBlank @Size(min = 3, max = 200)
            @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = TextRules.NO_CONTROL_CHARS_MESSAGE) String purpose,
            @NotNull UUID disburseAccountId) {
    }

    public record LoanDto(UUID id, BigDecimal amount, int tenureMonths, BigDecimal annualRate, BigDecimal emi,
                          LoanStatus status, BigDecimal outstandingPrincipal, LocalDate nextDueDate,
                          Instant createdAt) {
    }

    /** {@code amountDue} is what is still owed on the instalment (emi when untouched, 0 when PAID). */
    public record InstallmentDto(int installmentNo, LocalDate dueDate, BigDecimal emi, BigDecimal principal,
                                 BigDecimal interest, BigDecimal amountDue, InstallmentStatus status, Instant paidAt) {
    }

    /** LoanDto + {purpose, decisionReason, schedule}. */
    public record LoanDetailDto(UUID id, BigDecimal amount, int tenureMonths, BigDecimal annualRate, BigDecimal emi,
                                LoanStatus status, BigDecimal outstandingPrincipal, LocalDate nextDueDate,
                                Instant createdAt, String purpose, String decisionReason,
                                List<InstallmentDto> schedule) {
    }

    /** Without {@code amount}, pays the next due instalment; with it, pays the oldest dues first. */
    public record RepayRequest(
            @NotNull UUID accountId,
            @MoneyAmount(min = "1.00", max = "10000000.00") BigDecimal amount) {
    }

    public record RepaymentDto(UUID id, UUID loanId, BigDecimal amount, BigDecimal principalPaid,
                               BigDecimal interestPaid, Instant createdAt) {
    }

    // ------------------------------------------------------------------ admin (§7.7)

    public record Applicant(UUID userId, String fullName, String email) {
    }

    /** LoanDto + {applicant, monthlyIncome, purpose, decisionReason}. */
    public record AdminLoanDto(UUID id, BigDecimal amount, int tenureMonths, BigDecimal annualRate, BigDecimal emi,
                               LoanStatus status, BigDecimal outstandingPrincipal, LocalDate nextDueDate,
                               Instant createdAt, Applicant applicant, BigDecimal monthlyIncome, String purpose,
                               String decisionReason) {
    }

    public record ApproveRequest(
            @Size(max = 500)
            @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = TextRules.NO_CONTROL_CHARS_MESSAGE) String reason) {
    }

    public record RejectRequest(
            @NotBlank @Size(min = 5, max = 500)
            @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = TextRules.NO_CONTROL_CHARS_MESSAGE) String reason) {
    }
}
