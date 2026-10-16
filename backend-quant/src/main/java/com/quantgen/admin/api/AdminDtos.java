package com.quantgen.admin.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.auth.domain.Role;
import com.quantgen.common.web.TextRules;
import com.quantgen.loan.api.LoanDtos.LoanDto;

/** PRD §7.7 admin bodies (loan rows live in LoanDtos). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record AdminUserDto(UUID id, String fullName, String email, String phone, Role role, long accountCount,
                               Instant createdAt) {
    }

    /** AdminUserDto + {accounts, loans}. */
    public record AdminUserDetailDto(UUID id, String fullName, String email, String phone, Role role,
                                     long accountCount, Instant createdAt, List<AccountDto> accounts,
                                     List<LoanDto> loans) {
    }

    /** F-ADM-2: only ACTIVE or FROZEN; the reason is required and audited. */
    public record AccountStatusRequest(
            @NotNull AccountStatus status,
            @NotBlank @Size(min = 5, max = 500)
            @Pattern(regexp = TextRules.NO_CONTROL_CHARS, message = TextRules.NO_CONTROL_CHARS_MESSAGE) String reason) {
    }

    public record StatsDto(long totalUsers, BigDecimal totalDeposits, long activeLoans, BigDecimal outstandingPrincipal,
                           long tradesToday) {
    }
}
