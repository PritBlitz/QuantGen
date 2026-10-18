package com.quantgen.account.service;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * F-INS-1 categories for transactions (PRD 12.2 {@code TransactionDto.category}, {@code TransferDto.category}).
 * Implemented by the insights module; the account and payment modules only ask for categories by id.
 */
public interface TransactionCategorizer {

    /** Category key per ledger line of the user's accounts (lines of other users are omitted). */
    Map<UUID, String> categoriesOfLines(UUID userId, Collection<UUID> lineIds);

    /** Category key of the user's own line in each journal entry (the debit line when they have both sides). */
    Map<UUID, String> categoriesOfEntries(UUID userId, Collection<UUID> journalEntryIds);
}
