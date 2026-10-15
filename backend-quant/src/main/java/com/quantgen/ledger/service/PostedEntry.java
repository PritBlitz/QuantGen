package com.quantgen.ledger.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The result of a successful posting: the entry and its lines (with balance after each line). */
public record PostedEntry(UUID journalEntryId, Instant createdAt, List<LedgerLineView> lines) {

    /** The line posted to the given ledger account (the first, if there are several). */
    public LedgerLineView lineFor(UUID ledgerAccountId) {
        return lines.stream()
                .filter(l -> l.ledgerAccountId().equals(ledgerAccountId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No line for account " + ledgerAccountId));
    }
}
