package com.quantgen.ledger.service;

import java.util.Set;
import java.util.UUID;

/**
 * Lets the account module veto postings on frozen or closed customer accounts. Called by
 * {@code LedgerService.post} after the ledger rows are locked, so it sees the latest committed status.
 * Implemented in the account module; the ledger never reads account tables itself.
 */
public interface PostingGuard {

    /**
     * @param debited  customer ledger accounts that this entry debits (money leaving them)
     * @param credited customer ledger accounts that this entry credits (money arriving)
     * @throws com.quantgen.common.error.ApiException ACCOUNT_FROZEN if a frozen account would be debited,
     *         INVALID_STATE if a closed account is involved
     */
    void checkPostable(Set<UUID> debited, Set<UUID> credited);
}
