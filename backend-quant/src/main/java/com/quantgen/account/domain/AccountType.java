package com.quantgen.account.domain;

/** SAVINGS and CHECKING are everyday accounts (capped at 3); GOAL and FIXED_DEPOSIT sit outside the cap (D-037). */
public enum AccountType {
    SAVINGS, CHECKING, GOAL, FIXED_DEPOSIT;

    /** Can pay other people, bills and orders, and receive from them. */
    public boolean isEveryday() {
        return this == SAVINGS || this == CHECKING;
    }
}
