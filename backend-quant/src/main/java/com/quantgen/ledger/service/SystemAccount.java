package com.quantgen.ledger.service;

import java.util.UUID;

/** System ledger accounts seeded by V1__init.sql with fixed ids (F-LED-2). */
public enum SystemAccount {
    BANK_CASH("00000000-0000-0000-0000-000000000001"),
    LOAN_RECEIVABLE("00000000-0000-0000-0000-000000000002"),
    INTEREST_INCOME("00000000-0000-0000-0000-000000000003"),
    BROKERAGE_CLEARING("00000000-0000-0000-0000-000000000004");

    private final UUID id;

    SystemAccount(String id) {
        this.id = UUID.fromString(id);
    }

    public UUID id() {
        return id;
    }
}
