package com.quantgen.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;

/** PRD §7.3 AccountDto; {@code availableBalance} and {@code holdsTotal} are the v2 additions (§12.2). */
public record AccountDto(
        UUID id,
        String accountNumber,
        AccountType type,
        AccountStatus status,
        BigDecimal balance,
        BigDecimal availableBalance,
        BigDecimal holdsTotal,
        String currency,
        Instant createdAt) {
}
