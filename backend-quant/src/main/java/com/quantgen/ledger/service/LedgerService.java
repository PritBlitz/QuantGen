package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The only code path that changes balances (ARCHITECTURE §4). Other modules move money by building a
 * journal request and calling {@code post} inside their own transaction.
 */
public interface LedgerService {

    /** Creates an empty, credit-normal customer ledger account and returns its id. */
    UUID openCustomerAccount();

    /** Cached balances for the given ledger accounts (missing ids are omitted). */
    Map<UUID, BigDecimal> balancesOf(Collection<UUID> ledgerAccountIds);

    default BigDecimal balanceOf(UUID ledgerAccountId) {
        BigDecimal balance = balancesOf(java.util.List.of(ledgerAccountId)).get(ledgerAccountId);
        if (balance == null) {
            throw new IllegalArgumentException("Unknown ledger account " + ledgerAccountId);
        }
        return balance;
    }
}
