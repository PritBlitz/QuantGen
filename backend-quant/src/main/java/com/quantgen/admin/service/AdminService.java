package com.quantgen.admin.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.domain.AccountStatus;
import com.quantgen.account.service.AccountRef;
import com.quantgen.account.service.AccountService;
import com.quantgen.admin.api.AdminDtos.AdminUserDetailDto;
import com.quantgen.admin.api.AdminDtos.AdminUserDto;
import com.quantgen.admin.api.AdminDtos.StatsDto;
import com.quantgen.auth.api.AuthDtos.UserDto;
import com.quantgen.auth.service.UserService;
import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.AccountNumbers;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.common.web.PageDto;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.loan.api.LoanDtos.LoanDto;
import com.quantgen.loan.domain.LoanStatus;
import com.quantgen.loan.service.LoanService;
import com.quantgen.trading.service.OrderService;

/** F-ADM-1/2/4. Talks to other modules only through their services. Callers must be ADMIN (SecurityConfig). */
@Service
public class AdminService {

    static final int MAX_QUERY_LENGTH = 100;

    private final UserService users;
    private final AccountService accounts;
    private final LoanService loans;
    private final LedgerService ledger;
    private final OrderService orders;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public AdminService(UserService users, AccountService accounts, LoanService loans, LedgerService ledger,
                        OrderService orders, AuditService audit, NotificationService notifications, Clock clock) {
        this.users = users;
        this.accounts = accounts;
        this.loans = loans;
        this.ledger = ledger;
        this.orders = orders;
        this.audit = audit;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageDto<AdminUserDto> users(String q, Pageable pageable) {
        if (q != null && q.length() > MAX_QUERY_LENGTH) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "q", "must be at most " + MAX_QUERY_LENGTH + " characters");
        }
        Page<UserDto> page = users.search(q, pageable);
        Map<UUID, Long> counts = accounts.countAccountsByUser(page.getContent().stream().map(UserDto::id).toList());
        return PageDto.of(page, u -> new AdminUserDto(u.id(), u.fullName(), u.email(), u.phone(), u.role(),
                counts.getOrDefault(u.id(), 0L), u.createdAt()));
    }

    @Transactional(readOnly = true)
    public AdminUserDetailDto user(UUID userId) {
        UserDto u = users.getUser(userId);
        List<AccountDto> owned = accounts.accountsOfUser(userId);
        List<LoanDto> userLoans = loans.loansOfUser(userId);
        return new AdminUserDetailDto(u.id(), u.fullName(), u.email(), u.phone(), u.role(), owned.size(),
                u.createdAt(), owned, userLoans);
    }

    /** F-ADM-2: freeze or unfreeze, audited and notified in the same transaction. */
    @Transactional
    public AccountDto changeAccountStatus(UUID adminId, UUID accountId, AccountStatus status, String reason) {
        if (status != AccountStatus.ACTIVE && status != AccountStatus.FROZEN) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "status", "must be ACTIVE or FROZEN");
        }
        // Change first (locks, re-reads and checks the current status), then read the owner for audit/notify.
        AccountDto after = accounts.changeStatus(accountId, status);
        AccountRef account = accounts.findById(accountId).orElseThrow();
        String note = reason.trim();
        String masked = AccountNumbers.mask(account.accountNumber());
        boolean frozen = status == AccountStatus.FROZEN;
        audit.record(adminId, frozen ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN", "ACCOUNT", accountId.toString(),
                "Account " + masked + ": " + note);
        notifications.notify(account.userId(), frozen ? NotificationType.ACCOUNT_FROZEN : NotificationType.ACCOUNT_UNFROZEN,
                frozen ? "Account frozen" : "Account unfrozen",
                frozen
                        ? "Account " + masked + " was frozen. It can still receive money. Reason: " + note
                        : "Account " + masked + " is active again.");
        return after;
    }

    @Transactional(readOnly = true)
    public StatsDto stats() {
        Instant startOfDay = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE))
                .atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
        return new StatsDto(users.countCustomers(), ledger.totalCustomerBalances(),
                loans.countByStatus(LoanStatus.ACTIVE), loans.outstandingPrincipal(), orders.filledSince(startOfDay));
    }
}
