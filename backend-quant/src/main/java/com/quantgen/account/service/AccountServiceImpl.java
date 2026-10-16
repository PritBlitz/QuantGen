package com.quantgen.account.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.api.TransactionDto;
import com.quantgen.account.domain.Account;
import com.quantgen.account.domain.AccountRepository;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.domain.AccountType;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.LedgerLineView;
import com.quantgen.ledger.service.LedgerService;

@Service
class AccountServiceImpl implements AccountService {

    private static final int NUMBER_ATTEMPTS = 10;
    private static final int STATEMENT_DEFAULT_DAYS = 30;
    private static final int STATEMENT_MAX_DAYS = 366;
    private static final int STATEMENT_MAX_ROWS = 10_000;

    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final AccountNumberGenerator numbers;
    private final AdvisoryLocks locks;
    private final Clock clock;

    AccountServiceImpl(AccountRepository accounts, LedgerService ledger, AccountNumberGenerator numbers,
                       AdvisoryLocks locks, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.numbers = numbers;
        this.locks = locks;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ opening

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
        Account account = accounts.saveAndFlush(new Account(ledgerAccountId, userId, uniqueAccountNumber(), type));
        return toDto(account, BigDecimal.ZERO);
    }

    @Override
    @Transactional
    public AccountDto createAccount(UUID userId, AccountType type) {
        return openAccount(userId, type);
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

    // ------------------------------------------------------------------ reads

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> listAccounts(UUID userId) {
        return withBalances(accounts.findByUserIdOrderByCreatedAtAsc(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountDto> accountsOfUser(UUID userId) {
        return listAccounts(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Long> countAccountsByUser(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return accounts.countByUserIds(userIds).stream()
                .collect(Collectors.toMap(r -> (UUID) r[0], r -> (Long) r[1]));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountDto getAccount(UUID userId, UUID accountId) {
        Account account = owned(userId, accountId);
        return toDto(account, ledger.balanceOf(account.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public AccountRef requireOwned(UUID userId, UUID accountId) {
        return toRef(owned(userId, accountId));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRef> findByNumber(String accountNumber) {
        if (accountNumber == null || !accountNumber.matches("[0-9]{12}")) {
            return Optional.empty();
        }
        return accounts.findByAccountNumber(accountNumber).map(AccountServiceImpl::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountRef> findById(UUID accountId) {
        return accounts.findById(accountId).map(AccountServiceImpl::toRef);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal balanceOf(UUID accountId) {
        return ledger.balanceOf(accountId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageDto<TransactionDto> transactions(UUID userId, UUID accountId, LocalDate from, LocalDate to,
                                                JournalType type, Pageable pageable) {
        Account account = owned(userId, accountId);
        Range range = range(from, to);
        Page<LedgerLineView> page = ledger.history(account.getId(), range.from(), range.to(), type, pageable);
        return PageDto.of(page, TransactionDto::of);
    }

    // ------------------------------------------------------------------ statement

    @Override
    @Transactional(readOnly = true)
    public String statementCsv(UUID userId, UUID accountId, LocalDate from, LocalDate to) {
        Account account = owned(userId, accountId);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(STATEMENT_DEFAULT_DAYS - 1L);
        if (ChronoUnit.DAYS.between(start, end) >= STATEMENT_MAX_DAYS) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from",
                    "statement range can be at most " + STATEMENT_MAX_DAYS + " days");
        }
        Range range = range(start, end);
        List<LedgerLineView> lines = ledger.history(account.getId(), range.from(), range.to(), null,
                PageRequest.of(0, STATEMENT_MAX_ROWS)).getContent().reversed(); // oldest first
        return StatementCsv.render(account.getAccountNumber(), start, end, lines);
    }

    // ------------------------------------------------------------------ admin

    @Override
    @Transactional
    public AccountDto changeStatus(UUID accountId, AccountStatus status) {
        if (status == AccountStatus.CLOSED) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "must be ACTIVE or FROZEN");
        }
        if (!accounts.existsById(accountId)) {
            throw ApiException.notFound("Account");
        }
        // Take the ledger row lock first so a freeze and an in-flight posting are serialized.
        ledger.lockAccount(accountId);
        Account account = accounts.findById(accountId).orElseThrow(() -> ApiException.notFound("Account"));
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Account is closed");
        }
        if (account.getStatus() == status) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Account is already " + status.name().toLowerCase());
        }
        account.changeStatus(status);
        accounts.flush();
        return toDto(account, ledger.balanceOf(accountId));
    }

    // ------------------------------------------------------------------ helpers

    private Account owned(UUID userId, UUID accountId) {
        return accounts.findByIdAndUserId(accountId, userId).orElseThrow(() -> ApiException.notFound("Account"));
    }

    private List<AccountDto> withBalances(List<Account> owned) {
        Map<UUID, BigDecimal> balances = ledger.balancesOf(owned.stream().map(Account::getId).toList());
        return owned.stream().map(a -> toDto(a, balances.getOrDefault(a.getId(), BigDecimal.ZERO))).toList();
    }

    private record Range(Instant from, Instant to) {
    }

    /** IST calendar days [from, to] inclusive → [fromInstant, toInstant). */
    private static Range range(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must not be after 'to'");
        }
        Instant start = from == null ? Instant.EPOCH : from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        Instant end = to == null
                ? Instant.parse("9999-12-31T00:00:00Z")
                : to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return new Range(start, end);
    }

    static AccountRef toRef(Account a) {
        return new AccountRef(a.getId(), a.getUserId(), a.getAccountNumber(), a.getType(), a.getStatus());
    }

    static AccountDto toDto(Account a, BigDecimal balance) {
        return new AccountDto(a.getId(), a.getAccountNumber(), a.getType(), a.getStatus(), balance,
                a.getCurrency(), a.getCreatedAt());
    }
}
