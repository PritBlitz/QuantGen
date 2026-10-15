package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.quantgen.ledger.domain.Direction;
import com.quantgen.ledger.domain.JournalType;

/** A ledger line joined with its journal entry: one row of an account's transaction history. */
public record LedgerLineView(
        UUID lineId,
        UUID journalEntryId,
        UUID ledgerAccountId,
        Direction direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        JournalType type,
        String description,
        String counterparty,
        Instant createdAt) {
}
