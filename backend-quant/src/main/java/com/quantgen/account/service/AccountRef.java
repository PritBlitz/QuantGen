package com.quantgen.account.service;

import java.util.UUID;

import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;

/** A read-only view of an account for other modules. {@code id} is also its ledger account id. */
public record AccountRef(UUID id, UUID userId, String accountNumber, AccountType type, AccountStatus status) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
