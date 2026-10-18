package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.quantgen.ledger.domain.JournalType;

/**
 * The only code path that changes balances (ARCHITECTURE §4). Other modules move money by building a
 * {@link JournalRequest} and calling {@link #post} inside their own transaction, so the business record
 * and the ledger entry commit or roll back together.
 */
public interface LedgerService {

    /**
     * Posts a balanced journal entry: validates it, locks the ledger rows in ascending id order, rejects
     * negative customer balances (INSUFFICIENT_FUNDS) and debits of frozen accounts (ACCOUNT_FROZEN),
     * then inserts the entry and lines and updates the cached balances. Requires an active transaction.
     */
    PostedEntry post(JournalRequest request);

    /** Posts the mirror image of an entry (F-LED-7). An entry can be reversed at most once. */
    PostedEntry reverse(UUID journalEntryId, String reason, UUID createdBy);

    /** Creates an empty, credit-normal customer ledger account and returns its id. */
    UUID openCustomerAccount();

    /**
     * Locks one ledger account row. Callers changing an account's postability (freeze) take this lock
     * first, so a status change and a posting on the same account are serialized.
     */
    void lockAccount(UUID ledgerAccountId);

    /** Cached balances for the given ledger accounts (missing ids are omitted). */
    Map<UUID, BigDecimal> balancesOf(Collection<UUID> ledgerAccountIds);

    default BigDecimal balanceOf(UUID ledgerAccountId) {
        BigDecimal balance = balancesOf(List.of(ledgerAccountId)).get(ledgerAccountId);
        if (balance == null) {
            throw new IllegalArgumentException("Unknown ledger account " + ledgerAccountId);
        }
        return balance;
    }

    /**
     * Earmarks {@code amount} on a customer account without moving it (F-HOLD-1). Takes the same row lock as a
     * posting and requires {@code balance - active holds >= amount}, so a hold and a transfer can never both
     * spend the same rupee. Frozen accounts cannot place holds. Requires an active transaction.
     */
    UUID placeHold(UUID ledgerAccountId, BigDecimal amount, String reason, String reference, Instant expiresAt);

    /** ACTIVE to RELEASED. INVALID_STATE if the hold is not active. Releasing is allowed on frozen accounts. */
    void releaseHold(UUID holdId);

    /** Moves every ACTIVE hold past its {@code expires_at} to EXPIRED; returns how many. */
    int expireHolds(Instant now);

    /** Sum of ACTIVE holds per ledger account (accounts without holds map to zero). */
    Map<UUID, BigDecimal> holdsTotals(Collection<UUID> ledgerAccountIds);

    /** Sum of all customer balances: what the bank owes its depositors (admin stats). */
    BigDecimal totalCustomerBalances();

    /** An account's lines in [from, to), newest first, optionally of one type. */
    Page<LedgerLineView> history(UUID ledgerAccountId, Instant from, Instant to, JournalType type, Pageable pageable);

    /** The account's line(s) in one entry (used to build a transaction view after posting). */
    List<LedgerLineView> linesOf(UUID ledgerAccountId, UUID journalEntryId);

    /** F-LED-8: verifies every cached balance and that the whole ledger sums to zero. */
    ReconcileReport reconcile();
}
