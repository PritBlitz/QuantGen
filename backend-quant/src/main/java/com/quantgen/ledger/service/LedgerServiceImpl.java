package com.quantgen.ledger.service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.ledger.domain.LedgerAccount;
import com.quantgen.ledger.domain.LedgerAccountRepository;

@Service
class LedgerServiceImpl implements LedgerService {

    private final LedgerAccountRepository accounts;

    LedgerServiceImpl(LedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID openCustomerAccount() {
        return accounts.save(LedgerAccount.newCustomerAccount()).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, BigDecimal> balancesOf(Collection<UUID> ledgerAccountIds) {
        if (ledgerAccountIds.isEmpty()) {
            return Map.of();
        }
        return accounts.findByIdIn(ledgerAccountIds).stream()
                .collect(Collectors.toMap(LedgerAccount::getId, LedgerAccount::getBalance));
    }
}
