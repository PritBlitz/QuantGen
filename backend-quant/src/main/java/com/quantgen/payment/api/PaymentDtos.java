package com.quantgen.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.web.TextRules;
import com.quantgen.payment.domain.TransferStatus;

/** PRD §7.4 request and response bodies. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    private static final String NO_CONTROL_CHARS = TextRules.NO_CONTROL_CHARS;

    /** F-PAY-3: ₹1 to ₹2,00,000 per transfer. */
    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "must be a 12-digit account number") String toAccountNumber,
            @NotNull @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal amount,
            @Size(max = 140) @Pattern(regexp = NO_CONTROL_CHARS, message = "must not contain control characters") String note) {
    }

    public record TransferDto(
            UUID id,
            String fromAccountNumber,
            String toAccountNumber,
            String toName,
            BigDecimal amount,
            String note,
            TransferStatus status,
            Instant createdAt) {
    }

    public record BeneficiaryRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = NO_CONTROL_CHARS, message = "must not contain control characters") String nickname,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "must be a 12-digit account number") String accountNumber) {
    }

    public record BeneficiaryDto(UUID id, String nickname, String accountNumber, String holderName, Instant createdAt) {
    }
}
