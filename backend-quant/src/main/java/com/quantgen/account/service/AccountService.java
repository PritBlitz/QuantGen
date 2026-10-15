package com.quantgen.account.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    int MAX_ACCOUNTS_PER_CUSTOMER = 3;

    /** Opens an account for the user (F-ACC-1/2). Must run inside the caller's transaction. */
    AccountDto openAccount(UUID userId, AccountType type);

    /** Opens an account in its own transaction (API entry point). */
    AccountDto createAccount(UUID userId, AccountType type);

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

    /** CSV statement (F-ACC-6). Dates are IST calendar days, both inclusive; default is the last 30 days. */
    String statementCsv(UUID userId, UUID accountId, LocalDate from, LocalDate to);

    /** Admin: all accounts of a user. */
    List<AccountDto> accountsOfUser(UUID userId);

    /** Admin: freeze or unfreeze (F-ADM-2). Serialized with postings on the same account. */
    AccountDto changeStatus(UUID accountId, AccountStatus status);
}
