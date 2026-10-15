package com.quantgen.ledger.domain;

/** Business event types (PRD §7.3 TransactionDto.type). */
public enum JournalType {
    DEPOSIT, TRANSFER, TRADE, LOAN_DISBURSAL, LOAN_REPAYMENT, REVERSAL
}
