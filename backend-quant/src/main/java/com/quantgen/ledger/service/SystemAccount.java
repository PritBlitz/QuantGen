package com.quantgen.ledger.service;

import java.util.UUID;

/** System ledger accounts seeded by V1__init.sql and V10 with fixed ids (F-LED-2). */
public enum SystemAccount {
    BANK_CASH("00000000-0000-0000-0000-000000000001"),
    LOAN_RECEIVABLE("00000000-0000-0000-0000-000000000002"),
    INTEREST_INCOME("00000000-0000-0000-0000-000000000003"),
    BROKERAGE_CLEARING("00000000-0000-0000-0000-000000000004"),
    /** Bill payments owed onwards to billers (V10). */
    BILLER_CLEARING("00000000-0000-0000-0000-000000000005"),
    /** Money collected by the payment gateway for top-ups (V10). */
    GATEWAY_CLEARING("00000000-0000-0000-0000-000000000006"),
    /** Interest paid to customers on FDs and savings (V14). */
    INTEREST_EXPENSE("00000000-0000-0000-0000-000000000007");

    private final UUID id;

    SystemAccount(String id) {
        this.id = UUID.fromString(id);
    }

    public UUID id() {
        return id;
    }
}
