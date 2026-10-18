package com.quantgen.account.service;

import com.quantgen.common.error.ApiException;
import com.quantgen.account.domain.AccountType;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.api.TransactionDto;
import com.quantgen.common.idempotency.IdempotencyService;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.PostedEntry;
import com.quantgen.ledger.service.SystemAccount;

/** F-ACC-4 demo deposit: BANK_CASH (debit) → customer account (credit), idempotent. */
@Service
public class DepositService {

    private static final String DESCRIPTION = "Demo deposit";

    private final AccountService accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;

    public DepositService(AccountService accounts, LedgerService ledger, IdempotencyService idempotency) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
    }

    @Transactional
    public IdempotencyService.Result<TransactionDto> deposit(UUID userId, UUID accountId, UUID idempotencyKey,
                                                             String fingerprint, BigDecimal amount) {
        return idempotency.execute(userId, idempotencyKey, fingerprint, 201, TransactionDto.class, () -> {
            AccountRef account = accounts.requireOwned(userId, accountId);
            if (account.type() == AccountType.FIXED_DEPOSIT) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "accountId", "a fixed deposit can't take more money");
            }
            PostedEntry entry = ledger.post(JournalRequest.builder(JournalType.DEPOSIT, DESCRIPTION, userId)
                    .debit(SystemAccount.BANK_CASH.id(), amount, null)
                    .credit(account.id(), amount, "Add money")
                    .build());
            return TransactionDto.of(entry.lineFor(account.id())).withCategory("INCOME");
        });
    }
}
