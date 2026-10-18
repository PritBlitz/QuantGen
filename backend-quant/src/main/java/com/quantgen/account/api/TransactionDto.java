package com.quantgen.account.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.quantgen.ledger.domain.Direction;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.LedgerLineView;

/** PRD §7.3 TransactionDto: one ledger line of a customer account; {@code category} is the v2 addition (§12.2). */
public record TransactionDto(
        UUID id,
        UUID journalEntryId,
        UUID accountId,
        Direction direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        JournalType type,
        String description,
        String counterparty,
        Instant createdAt,
        String category) {

    /** Without a category; see {@link #withCategory}. */
    public static TransactionDto of(LedgerLineView line) {
        return new TransactionDto(line.lineId(), line.journalEntryId(), line.ledgerAccountId(), line.direction(),
                line.amount(), line.balanceAfter(), line.type(), line.description(), line.counterparty(),
                line.createdAt(), null);
    }

    public TransactionDto withCategory(String category) {
        return new TransactionDto(id, journalEntryId, accountId, direction, amount, balanceAfter, type, description,
                counterparty, createdAt, category);
    }
}
