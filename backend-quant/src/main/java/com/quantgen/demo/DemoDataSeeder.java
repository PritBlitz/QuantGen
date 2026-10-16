package com.quantgen.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.quantgen.account.api.AccountDto;
import com.quantgen.account.domain.AccountType;
import com.quantgen.account.service.AccountService;
import com.quantgen.account.service.DepositService;
import com.quantgen.auth.service.DemoUsers;
import com.quantgen.loan.api.LoanDtos.ApplyRequest;
import com.quantgen.loan.api.LoanDtos.LoanDto;
import com.quantgen.loan.api.LoanDtos.RepayRequest;
import com.quantgen.loan.domain.LoanStatus;
import com.quantgen.loan.service.LoanService;
import com.quantgen.payment.api.PaymentDtos.TransferRequest;
import com.quantgen.payment.service.TransferService;
import com.quantgen.trading.api.TradingDtos.OrderRequest;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.service.OrderService;
import com.quantgen.trading.service.PriceSimulator;

/**
 * PRD §10 demo data, for the {@code dev} profile and the {@code demo} profile of the live deployment:
 * {@code admin@quantgen.dev} and {@code demo@quantgen.dev} (passwords from QG_DEMO_ADMIN_PASSWORD and
 * QG_DEMO_USER_PASSWORD), funded accounts, holdings, transfers, one active loan, one pending application,
 * and a week of price history.
 *
 * <p>Everything goes through the real services, so it obeys the same ledger rules as user traffic. Each
 * money movement uses a deterministic idempotency key, so restarting replays instead of repeating it.
 * Never active in {@code test} or plain {@code prod}.
 */
@Component
@Profile({"dev", "demo"})
class DemoDataSeeder implements ApplicationRunner {

    static final String ADMIN_EMAIL = "admin@quantgen.dev";
    static final String DEMO_EMAIL = "demo@quantgen.dev";
    static final String PEER_EMAIL = "riya@quantgen.dev";
    static final String DEFAULT_ADMIN_PASSWORD = "Admin@1234";
    static final String DEFAULT_USER_PASSWORD = "Demo@1234";

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final DemoUsers demoUsers;
    private final AccountService accounts;
    private final DepositService deposits;
    private final TransferService transfers;
    private final OrderService orders;
    private final LoanService loans;
    private final PriceSimulator prices;
    private final Environment env;
    private final String adminPassword;
    private final String userPassword;

    DemoDataSeeder(DemoUsers demoUsers, AccountService accounts, DepositService deposits, TransferService transfers,
                   OrderService orders, LoanService loans, PriceSimulator prices, Environment env,
                   @Value("${qg.demo.admin-password:" + DEFAULT_ADMIN_PASSWORD + "}") String adminPassword,
                   @Value("${qg.demo.user-password:" + DEFAULT_USER_PASSWORD + "}") String userPassword) {
        this.demoUsers = demoUsers;
        this.accounts = accounts;
        this.deposits = deposits;
        this.transfers = transfers;
        this.orders = orders;
        this.loans = loans;
        this.prices = prices;
        this.env = env;
        this.adminPassword = adminPassword;
        this.userPassword = userPassword;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (Arrays.asList(env.getActiveProfiles()).contains("prod")
                && (DEFAULT_ADMIN_PASSWORD.equals(adminPassword) || DEFAULT_USER_PASSWORD.equals(userPassword))) {
            log.warn("Demo users have the published default passwords. Set QG_DEMO_ADMIN_PASSWORD and "
                    + "QG_DEMO_USER_PASSWORD on any public deployment.");
        }
        try {
            seed();
        } catch (RuntimeException e) {
            // On a live demo, visitors may have frozen or drained the demo accounts; never fail startup over it.
            log.error("Demo data seeding stopped early; the app runs without the remaining demo data", e);
        }
    }

    void seed() {
        UUID adminId = demoUsers.ensureAdmin("QuantGen Admin", ADMIN_EMAIL, "+919800000001", adminPassword);
        UUID demoId = demoUsers.ensureCustomer("Aarav Mehta", DEMO_EMAIL, "+919800000002", userPassword);
        // A counterparty for transfers. Nobody needs to log in as Riya, so her password is random each boot.
        UUID peerId = demoUsers.ensureCustomer("Riya Sharma", PEER_EMAIL, "+919800000003",
                "Rq!" + UUID.randomUUID() + "a1");

        List<AccountDto> demoAccounts = accounts.listAccounts(demoId);
        if (demoAccounts.size() < 2) {
            accounts.createAccount(demoId, AccountType.CHECKING);
            demoAccounts = accounts.listAccounts(demoId);
        }
        UUID savings = demoAccounts.get(0).id();
        UUID checking = demoAccounts.get(1).id();
        AccountDto peerAccount = accounts.listAccounts(peerId).getFirst();

        deposit(demoId, savings, "salary-1", "100000.00");
        deposit(demoId, savings, "salary-2", "100000.00");
        deposit(demoId, checking, "checking-1", "50000.00");
        deposit(peerId, peerAccount.id(), "peer-1", "25000.00");

        buy(demoId, savings, "QGEN", 20);
        buy(demoId, savings, "TCS", 10);
        buy(demoId, savings, "INFY", 25);
        buy(demoId, savings, "HDFC", 15);
        buy(demoId, savings, "RELI", 8);

        transfer(demoId, savings, peerAccount.accountNumber(), "dinner", "2500.00", "Dinner split");
        transfer(peerId, peerAccount.id(), demoAccounts.get(0).accountNumber(), "movie", "1200.00", "Movie tickets");
        transfer(demoId, savings, demoAccounts.get(1).accountNumber(), "own", "10000.00", "Monthly budget");

        seedLoans(adminId, demoId, savings);

        int ticks = prices.backfill(Duration.ofDays(7), Duration.ofMinutes(5));
        log.info("Demo data ready ({} and {}; {} price ticks backfilled)", ADMIN_EMAIL, DEMO_EMAIL, ticks);
    }

    private void seedLoans(UUID adminId, UUID demoId, UUID savings) {
        List<LoanDto> existing = loans.list(demoId);
        if (existing.isEmpty()) {
            LoanDto loan = loans.apply(demoId, new ApplyRequest(new BigDecimal("200000.00"), 24,
                    new BigDecimal("150000.00"), "Home renovation", savings));
            loans.approve(adminId, loan.id(), "Salary verified; strong repayment capacity");
            loans.repay(demoId, loan.id(), key("loan-emi-1"), "SEED loan-emi-1", new RepayRequest(savings, null));
            existing = loans.list(demoId);
        }
        if (existing.stream().noneMatch(l -> l.status() == LoanStatus.PENDING)) {
            // Something for the admin loan queue.
            loans.apply(demoId, new ApplyRequest(new BigDecimal("60000.00"), 12, new BigDecimal("150000.00"),
                    "Two-wheeler purchase", savings));
        }
    }

    private void deposit(UUID userId, UUID accountId, String name, String amount) {
        deposits.deposit(userId, accountId, key("deposit-" + name), "SEED deposit-" + name + " " + amount,
                new BigDecimal(amount));
    }

    private void buy(UUID userId, UUID accountId, String symbol, long quantity) {
        orders.place(userId, key("buy-" + symbol), "SEED buy-" + symbol + " " + quantity,
                new OrderRequest(accountId, symbol, OrderSide.BUY, quantity));
    }

    private void transfer(UUID userId, UUID from, String toNumber, String name, String amount, String note) {
        transfers.transfer(userId, key("transfer-" + name), "SEED transfer-" + name + " " + amount,
                new TransferRequest(from, toNumber, new BigDecimal(amount), note));
    }

    /** The same key every boot, so the idempotency layer replays instead of moving money twice. */
    private static UUID key(String name) {
        return UUID.nameUUIDFromBytes(("quantgen-demo:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
