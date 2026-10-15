package com.quantgen.account.service;

import java.util.List;
import java.util.UUID;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.domain.AccountType;

public interface AccountService {

    int MAX_ACCOUNTS_PER_CUSTOMER = 3;

    /** Opens an account for the user (F-ACC-1/2). Must run inside the caller's transaction. */
    AccountDto openAccount(UUID userId, AccountType type);

    /** The user's accounts, oldest first. */
    List<AccountDto> listAccounts(UUID userId);
}
