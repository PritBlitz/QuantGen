package com.quantgen.account.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.domain.Account;
import com.quantgen.account.domain.AccountRepository;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.ledger.service.LedgerService;

@Service
class AccountServiceImpl implements AccountService {

    private static final int NUMBER_ATTEMPTS = 10;

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AccountNumberGenerator numbers;
    private final AdvisoryLocks locks;

    AccountServiceImpl(AccountRepository accounts, LedgerService ledger, AccountNumberGenerator numbers,
                       AdvisoryLocks locks) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.numbers = numbers;
        this.locks = locks;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AccountDto openAccount(UUID userId, AccountType type) {
        // Serialize account opening per user so concurrent requests can't exceed the limit.
        locks.lockForTransaction("accounts:" + userId);
        if (accounts.countByUserIdAndStatusNot(userId, AccountStatus.CLOSED) >= MAX_ACCOUNTS_PER_CUSTOMER) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED,
                    "A customer can hold at most " + MAX_ACCOUNTS_PER_CUSTOMER + " accounts");
        }
        UUID ledgerAccountId = ledger.openCustomerAccount();
        Account account = accounts.save(new Account(ledgerAccountId, userId, uniqueAccountNumber(), type));
        accounts.flush();
        return toDto(account, BigDecimal.ZERO);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> listAccounts(UUID userId) {
        List<Account> owned = accounts.findByUserIdOrderByCreatedAtAsc(userId);
        Map<UUID, BigDecimal> balances = ledger.balancesOf(owned.stream().map(Account::getId).toList());
        return owned.stream().map(a -> toDto(a, balances.getOrDefault(a.getId(), BigDecimal.ZERO))).toList();
    }

    private String uniqueAccountNumber() {
        for (int i = 0; i < NUMBER_ATTEMPTS; i++) {
            String candidate = numbers.next();
            if (!accounts.existsByAccountNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not allocate a unique account number");
    }

    static AccountDto toDto(Account a, BigDecimal balance) {
        return new AccountDto(a.getId(), a.getAccountNumber(), a.getType(), a.getStatus(), balance,
                a.getCurrency(), a.getCreatedAt());
    }
}
