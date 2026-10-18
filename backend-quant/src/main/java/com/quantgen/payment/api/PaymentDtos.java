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
import com.quantgen.payment.domain.TransferSource;
import com.quantgen.payment.domain.TransferStatus;

/** PRD §7.4 request and response bodies. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    private static final String NO_CONTROL_CHARS = TextRules.NO_CONTROL_CHARS;

    /** Handles are matched case-insensitively; the server stores them lower-cased (F-PAY-5). */
    public static final String HANDLE_PATTERN = "^[A-Za-z0-9.]{3,30}@[qQ][gG]$";

    /** F-PAY-3: ₹1 to ₹2,00,000 per transfer. Exactly one of {@code toAccountNumber} and {@code toHandle} (12.4). */
    public record TransferRequest(
            @NotNull UUID fromAccountId,
            @Pattern(regexp = "^[0-9]{12}$", message = "must be a 12-digit account number") String toAccountNumber,
            @Pattern(regexp = HANDLE_PATTERN, message = "must look like name@qg") String toHandle,
            @NotNull @MoneyAmount(min = "1.00", max = "200000.00") BigDecimal amount,
            @Size(max = 140) @Pattern(regexp = NO_CONTROL_CHARS, message = "must not contain control characters") String note) {

        public TransferRequest(UUID fromAccountId, String toAccountNumber, BigDecimal amount, String note) {
            this(fromAccountId, toAccountNumber, null, amount, note);
        }
    }

    public record TransferDto(
            UUID id,
            String fromAccountNumber,
            String toAccountNumber,
            String toName,
            BigDecimal amount,
            String note,
            TransferStatus status,
            Instant createdAt,
            TransferSource source,
            String category) {
    }

    public record BeneficiaryRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = NO_CONTROL_CHARS, message = "must not contain control characters") String nickname,
            @NotBlank @Pattern(regexp = "^[0-9]{12}$", message = "must be a 12-digit account number") String accountNumber) {
    }

    public record BeneficiaryDto(UUID id, String nickname, String accountNumber, String holderName, Instant createdAt) {
    }
}
