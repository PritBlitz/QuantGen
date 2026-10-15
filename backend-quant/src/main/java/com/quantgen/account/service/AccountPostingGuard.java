package com.quantgen.account.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.quantgen.account.domain.Account;
import com.quantgen.account.domain.AccountRepository;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.ledger.service.PostingGuard;

/** F-ACC-3: a frozen account can receive but not send; a closed account can do neither. */
@Component
class AccountPostingGuard implements PostingGuard {

    private final AccountRepository accounts;

    AccountPostingGuard(AccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    public void checkPostable(Set<UUID> debited, Set<UUID> credited) {
        Set<UUID> all = new HashSet<>(debited);
        all.addAll(credited);
        if (all.isEmpty()) {
            return;
        }
        List<Account> found = accounts.findAllById(all);
        for (Account account : found) {
            if (account.getStatus() == AccountStatus.CLOSED) {
                throw new ApiException(ErrorCode.INVALID_STATE, "Account is closed");
            }
            if (account.getStatus() == AccountStatus.FROZEN && debited.contains(account.getId())) {
                throw new ApiException(ErrorCode.ACCOUNT_FROZEN, "Account is frozen and cannot send money");
            }
        }
    }
}
