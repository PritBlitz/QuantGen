package com.quantgen.account.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.api.TransactionDto;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;

/**
 * Accounts (F-ACC-*). Every method taking a {@code userId} enforces ownership: someone else's account is
 * reported as NOT_FOUND, never FORBIDDEN.
 */
public interface AccountService {

    /** Everyday accounts (SAVINGS, CHECKING) per customer; goals and FDs don't count (D-037). */
    int MAX_ACCOUNTS_PER_CUSTOMER = 3;
    int MAX_GOALS = 10;

    /** F-SAV-1: what a goal is saving for. */
    record GoalSpec(String name, BigDecimal target, LocalDate targetDate, String emoji) {
    }

    /** Opens an account for the user (F-ACC-1/2). Must run inside the caller's transaction. */
    AccountDto openAccount(UUID userId, AccountType type);

    /** Opens an account in its own transaction (API entry point). */
    AccountDto createAccount(UUID userId, AccountType type);

    /** F-SAV-1: opens a GOAL account (at most {@link #MAX_GOALS} open ones). */
    AccountDto createGoal(UUID userId, GoalSpec goal);

    /** F-SAV-3: opens the FIXED_DEPOSIT account an FD's money sits in. Must run inside the caller's transaction. */
    AccountRef openFixedDepositAccount(UUID userId);

    /**
     * Closes an emptied goal or FD account (balance zero, no active holds; INVALID_STATE otherwise). Serialized with
     * postings on it. Must run inside the caller's transaction.
     */
    void closeEmptyAccount(UUID accountId);

    /** The user's accounts, oldest first. */
    List<AccountDto> listAccounts(UUID userId);

    AccountDto getAccount(UUID userId, UUID accountId);

    /** The account if the user owns it; NOT_FOUND otherwise. */
    AccountRef requireOwned(UUID userId, UUID accountId);

    Optional<AccountRef> findByNumber(String accountNumber);

    Optional<AccountRef> findById(UUID accountId);

    BigDecimal balanceOf(UUID accountId);

    /** Paginated history (F-ACC-5). Dates are IST calendar days, both inclusive; nulls mean unbounded. */
    PageDto<TransactionDto> transactions(UUID userId, UUID accountId, LocalDate from, LocalDate to, JournalType type,
                                         Pageable pageable);

    /** One transaction (a line of one of the user's accounts) with its category; NOT_FOUND otherwise. */
    TransactionDto transaction(UUID userId, UUID lineId);

    /** CSV statement (F-ACC-6). Dates are IST calendar days, both inclusive; default is the last 30 days. */
    String statementCsv(UUID userId, UUID accountId, LocalDate from, LocalDate to);

    /** Admin: all accounts of a user. */
    List<AccountDto> accountsOfUser(UUID userId);

    /** Admin: number of accounts (any status) per user; users without accounts are omitted. */
    Map<UUID, Long> countAccountsByUser(Collection<UUID> userIds);

    /**
     * Admin: freeze or unfreeze (F-ADM-2). Serialized with postings on the same account; INVALID_STATE if
     * the account already has that status (checked under the lock). Call it before reading the account
     * in the same transaction, so the status check sees the latest committed row.
     */
    AccountDto changeStatus(UUID accountId, AccountStatus status);
}
