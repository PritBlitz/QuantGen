package com.quantgen.loan.domain;

/** F-LOAN-4: PENDING → APPROVED → ACTIVE → CLOSED, or PENDING → REJECTED. */
public enum LoanStatus {
    PENDING, APPROVED, ACTIVE, CLOSED, REJECTED
}
