package com.quantgen.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;

/** PRD §7.3 AccountDto. */
public record AccountDto(
        UUID id,
        String accountNumber,
        AccountType type,
        AccountStatus status,
        BigDecimal balance,
        String currency,
        Instant createdAt) {
}
