package com.quantgen.loan;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.loan.service.EmiAutoDebitJob;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** M4: quote, apply, admin approve/reject, disbursal, schedule, repay (idempotent), auto-debit job, overdue. */
class LoanIT extends IntegrationTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired
    EmiAutoDebitJob job;

    @Autowired
    LedgerService ledger;

    // ------------------------------------------------------------------ helpers

    private Response quote(TestUsers.Session s, String amount, int tenure, String income) {
        return postJson("/api/v1/loans/quote",
                Map.of("amount", amount, "tenureMonths", tenure, "monthlyIncome", income), Bank.auth(s));
    }

    private Response apply(TestUsers.Session s, String amount, int tenure, String income, UUID account) {
        return postJson("/api/v1/loans", Map.of("amount", amount, "tenureMonths", tenure, "monthlyIncome", income,
                "purpose", "Home renovation", "disburseAccountId", account.toString()), Bank.auth(s));
    }

    private Response approve(TestUsers.Session admin, String loanId) {
        return postJson("/api/v1/admin/loans/" + loanId + "/approve", Map.of("reason", "Meets criteria"),
                Bank.auth(admin));
    }

    private Response repay(TestUsers.Session s, String loanId, UUID account, String amount, UUID key) {
        Map<String, Object> body = new HashMap<>();
        body.put("accountId", account.toString());
        if (amount != null) {
            body.put("amount", amount);
        }
        return postJson("/api/v1/loans/" + loanId + "/repay", body, Bank.authWithKey(s, key));
    }

    /** A customer with an approved (ACTIVE) loan; returns the loan id. */
    private String activeLoan(TestUsers.Session s, TestUsers.Session admin, String amount, int tenure) {
        Response applied = apply(s, amount, tenure, "200000.00", Bank.firstAccountId(this, s));
        assertThat(applied.status()).as(applied.body()).isEqualTo(201);
        String id = applied.jsonBody().get("id").asString();
        Response approved = approve(admin, id);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return id;
    }

    private JsonNode detail(TestUsers.Session s, String loanId) {
        return get("/api/v1/loans/" + loanId, s.accessToken()).jsonBody();
    }

    private int notifications(UUID userId, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?", Integer.class,
                userId, type);
    }

    private static LocalDate firstDueDate() {
        return LocalDate.now(IST).plusMonths(1);
    }

    // ------------------------------------------------------------------ quote and apply

    @Test
    void quoteReturnsStringMoneyAndEligibility() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        JsonNode ok = quote(s, "100000.00", 12, "50000.00").jsonBody();
        assertThat(ok.get("emi").asString()).isEqualTo("8884.88");
        assertThat(ok.get("totalPayable").isString()).isTrue();
        assertThat(ok.get("eligible").asBoolean()).isTrue();
        assertThat(ok.has("reason")).isFalse();

        // 20× income: 4,00,000 > 20 × 15,000
        JsonNode tooBig = quote(s, "400000.00", 60, "15000.00").jsonBody();
        assertThat(tooBig.get("eligible").asBoolean()).isFalse();
        assertThat(tooBig.get("reason").asString()).contains("20 times");

        // EMI 8884.88 > 50% of 15,000
        JsonNode emiTooHigh = quote(s, "100000.00", 12, "15000.00").jsonBody();
        assertThat(emiTooHigh.get("eligible").asBoolean()).isFalse();
        assertThat(emiTooHigh.get("reason").asString()).contains("50%");

        Response invalid = quote(s, "9999.99", 5, "-1");
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.jsonBody().get("fieldErrors").size()).isEqualTo(3);
    }

    @Test
    void applyValidatesOwnershipAndEligibilityAndCountsPendingEmis() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID account = Bank.firstAccountId(this, s);

        assertThat(apply(s, "50000.00", 12, "40000.00", Bank.firstAccountId(this, other)).status()).isEqualTo(404);

        Response notEligible = apply(s, "100000.00", 12, "15000.00", account);
        assertThat(notEligible.status()).isEqualTo(422);
        assertThat(notEligible.jsonBody().get("code").asString()).isEqualTo("LOAN_NOT_ELIGIBLE");

        // EMI 4442.44 against income 10,000 is fine once...
        Response first = apply(s, "50000.00", 12, "10000.00", account);
        assertThat(first.status()).isEqualTo(201);
        JsonNode loan = first.jsonBody();
        assertThat(loan.get("status").asString()).isEqualTo("PENDING");
        assertThat(loan.get("annualRate").asString()).isEqualTo("12.00");
        assertThat(loan.get("outstandingPrincipal").asString()).isEqualTo("0.00");
        assertThat(loan.get("nextDueDate").isNull()).isTrue();
        // ...but a second pending application would push total EMIs past 50%.
        Response second = apply(s, "50000.00", 12, "10000.00", account);
        assertThat(second.status()).isEqualTo(422);
        assertThat(second.jsonBody().get("message").asString()).contains("existing EMIs");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'LOAN_APPLIED' AND target_id = ?",
                Integer.class, loan.get("id").asString())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    void fullLifecycleApproveDisburseRepayClose() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID account = Bank.firstAccountId(this, s);

        String loanId = apply(s, "60000.00", 6, "50000.00", account).jsonBody().get("id").asString();
        Bank.deposit(this, s, account, "5000.00"); // the interest has to come from somewhere

        // Customers can't reach the admin queue; admins see the applicant.
        assertThat(get("/api/v1/admin/loans?status=PENDING", s.accessToken()).status()).isEqualTo(403);
        JsonNode queue = get("/api/v1/admin/loans?status=PENDING&size=100", admin.accessToken()).jsonBody();
        JsonNode row = null;
        for (JsonNode n : queue.get("content")) {
            if (n.get("id").asString().equals(loanId)) {
                row = n;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("applicant").get("email").asString()).isEqualTo(s.email());
        assertThat(row.get("monthlyIncome").asString()).isEqualTo("50000.00");
        assertThat(row.get("purpose").asString()).isEqualTo("Home renovation");

        // Approve → ACTIVE, disbursed, schedule generated, audited, notified.
        Response approved = approve(admin, loanId);
        assertThat(approved.status()).isEqualTo(200);
        assertThat(approved.jsonBody().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(approved.jsonBody().get("nextDueDate").asString()).isEqualTo(firstDueDate().toString());
        assertThat(Bank.balance(this, s, account)).isEqualTo("65000.00");
        assertThat(approve(admin, loanId).status()).isEqualTo(409);
        assertThat(postJson("/api/v1/admin/loans/" + loanId + "/reject", Map.of("reason", "Too late now"),
                Bank.auth(admin)).status()).isEqualTo(409);
        assertThat(notifications(s.userId(), "LOAN_APPROVED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'LOAN_APPROVED' AND target_id = ?",
                Integer.class, loanId)).isEqualTo(1);

        JsonNode d = detail(s, loanId);
        assertThat(d.get("decisionReason").asString()).isEqualTo("Meets criteria");
        assertThat(d.get("outstandingPrincipal").asString()).isEqualTo("60000.00");
        JsonNode schedule = d.get("schedule");
        assertThat(schedule.size()).isEqualTo(6);
        assertThat(schedule.get(0).get("dueDate").asString()).isEqualTo(firstDueDate().toString());
        assertThat(schedule.get(0).get("interest").asString()).isEqualTo("600.00");
        String emi = d.get("emi").asString();

        // Pay the next EMI; replaying the same key returns the same repayment and debits once.
        UUID key = UUID.randomUUID();
        Response paid = repay(s, loanId, account, null, key);
        assertThat(paid.status()).as(paid.body()).isEqualTo(201);
        JsonNode rep = paid.jsonBody();
        assertThat(rep.get("amount").asString()).isEqualTo(emi);
        assertThat(rep.get("interestPaid").asString()).isEqualTo("600.00");
        Response replay = repay(s, loanId, account, null, key);
        assertThat(replay.status()).isEqualTo(201);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(replay.jsonBody().get("id").asString()).isEqualTo(rep.get("id").asString());
        assertThat(repay(s, loanId, account, "5.00", key).status()).isEqualTo(409); // same key, different body
        assertThat(new BigDecimal(Bank.balance(this, s, account)))
                .isEqualByComparingTo(new BigDecimal("65000.00").subtract(new BigDecimal(emi)));

        d = detail(s, loanId);
        assertThat(d.get("schedule").get(0).get("status").asString()).isEqualTo("PAID");
        assertThat(d.get("schedule").get(0).get("paidAt").isNull()).isFalse();
        assertThat(d.get("nextDueDate").asString()).isEqualTo(firstDueDate().plusMonths(1).toString());

        // A custom amount larger than everything still due is rejected; the exact remainder closes the loan.
        BigDecimal remaining = BigDecimal.ZERO;
        for (int i = 1; i < 6; i++) {
            remaining = remaining.add(new BigDecimal(d.get("schedule").get(i).get("emi").asString()));
        }
        Response tooMuch = repay(s, loanId, account, remaining.add(new BigDecimal("0.01")).toPlainString(),
                UUID.randomUUID());
        assertThat(tooMuch.status()).isEqualTo(400);
        assertThat(tooMuch.jsonBody().get("fieldErrors").get(0).get("field").asString()).isEqualTo("amount");

        Response rest = repay(s, loanId, account, remaining.toPlainString(), UUID.randomUUID());
        assertThat(rest.status()).as(rest.body()).isEqualTo(201);
        d = detail(s, loanId);
        assertThat(d.get("status").asString()).isEqualTo("CLOSED");
        assertThat(d.get("outstandingPrincipal").asString()).isEqualTo("0.00");
        assertThat(d.get("nextDueDate").isNull()).isTrue();
        assertThat(repay(s, loanId, account, null, UUID.randomUUID()).status()).isEqualTo(409);

        // Interest went to INTEREST_INCOME, and the ledger still reconciles.
        BigDecimal interest = jdbc.queryForObject("SELECT sum(interest_paid) FROM loan_repayments WHERE loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(interest).isEqualByComparingTo(jdbc.queryForObject(
                "SELECT sum(interest) FROM loan_installments WHERE loan_id = ?::uuid", BigDecimal.class, loanId));
        assertThat(ledger.reconcile().balanced()).isTrue();
        assertThat(notifications(s.userId(), "EMI_PAID")).isEqualTo(2);
    }

    @Test
    void partialRepaymentGoesToInterestFirstThenPrincipal() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID account = Bank.firstAccountId(this, s);
        String loanId = activeLoan(s, admin, "100000.00", 12);

        JsonNode rep = repay(s, loanId, account, "1500.00", UUID.randomUUID()).jsonBody();
        assertThat(rep.get("interestPaid").asString()).isEqualTo("1000.00");
        assertThat(rep.get("principalPaid").asString()).isEqualTo("500.00");
        JsonNode d = detail(s, loanId);
        assertThat(d.get("outstandingPrincipal").asString()).isEqualTo("99500.00");
        assertThat(d.get("schedule").get(0).get("status").asString()).isEqualTo("PENDING");
        // Without an amount, the remainder of the partly paid instalment is what's due next.
        JsonNode next = repay(s, loanId, account, null, UUID.randomUUID()).jsonBody();
        assertThat(next.get("amount").asString()).isEqualTo("7384.88");
        assertThat(detail(s, loanId).get("schedule").get(0).get("status").asString()).isEqualTo("PAID");
    }

    @Test
    void rejectNeedsAReasonAndNotifies() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        String loanId = apply(s, "20000.00", 6, "30000.00", Bank.firstAccountId(this, s)).jsonBody().get("id").asString();

        assertThat(postJson("/api/v1/admin/loans/" + loanId + "/reject", Map.of(), Bank.auth(admin)).status())
                .isEqualTo(400);
        assertThat(postJson("/api/v1/admin/loans/" + loanId + "/reject", Map.of("reason", "no"), Bank.auth(admin))
                .status()).isEqualTo(400);
        Response rejected = postJson("/api/v1/admin/loans/" + loanId + "/reject",
                Map.of("reason", "Income could not be verified"), Bank.auth(admin));
        assertThat(rejected.status()).isEqualTo(200);
        assertThat(rejected.jsonBody().get("status").asString()).isEqualTo("REJECTED");
        assertThat(detail(s, loanId).get("decisionReason").asString()).isEqualTo("Income could not be verified");
        assertThat(approve(admin, loanId).status()).isEqualTo(409);
        assertThat(notifications(s.userId(), "LOAN_REJECTED")).isEqualTo(1);
        assertThat(postJson("/api/v1/admin/loans/" + UUID.randomUUID() + "/approve", Map.of(), Bank.auth(admin))
                .status()).isEqualTo(404);
    }

    @Test
    void anotherUsersLoanIsNotFound() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        String loanId = activeLoan(s, admin, "30000.00", 6);

        assertThat(get("/api/v1/loans/" + loanId, other.accessToken()).status()).isEqualTo(404);
        assertThat(repay(other, loanId, Bank.firstAccountId(this, other), null, UUID.randomUUID()).status())
                .isEqualTo(404);
        // The owner can't repay from someone else's account either.
        assertThat(repay(s, loanId, Bank.firstAccountId(this, other), null, UUID.randomUUID()).status())
                .isEqualTo(404);
        assertThat(get("/api/v1/loans", other.accessToken()).jsonBody().size()).isZero();
    }

    @Test
    void anAdminCannotDecideTheirOwnApplication() {
        TestUsers.Session admin = Bank.admin(this);
        String loanId = apply(admin, "20000.00", 6, "30000.00", Bank.firstAccountId(this, admin))
                .jsonBody().get("id").asString();
        assertThat(approve(admin, loanId).status()).isEqualTo(409);
    }

    // ------------------------------------------------------------------ daily job

    @Test
    void autoDebitPaysDueEmisSendsRemindersAndMarksOverdue() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session sink = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID account = Bank.firstAccountId(this, s);
        String loanId = activeLoan(s, admin, "60000.00", 6);
        LocalDate due1 = firstDueDate();

        // Three days ahead: a reminder, sent once.
        job.runFor(due1.minusDays(3));
        job.runFor(due1.minusDays(3));
        assertThat(notifications(s.userId(), "EMI_DUE")).isEqualTo(1);

        // Due date: paid from the disbursal account.
        EmiAutoDebitJob.Result r1 = job.runFor(due1);
        assertThat(r1.paid()).isGreaterThanOrEqualTo(1);
        JsonNode d = detail(s, loanId);
        assertThat(d.get("schedule").get(0).get("status").asString()).isEqualTo("PAID");
        assertThat(d.get("schedule").get(1).get("status").asString()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT source FROM loan_repayments WHERE loan_id = ?::uuid", String.class, loanId))
                .isEqualTo("AUTO_DEBIT");

        // Empty the account: the next EMI can't be debited → OVERDUE, notified once even if the job reruns.
        String balance = Bank.balance(this, s, account);
        assertThat(Bank.transfer(this, s, account, Bank.firstAccountNumber(this, sink), balance, UUID.randomUUID())
                .status()).isEqualTo(201);
        LocalDate due2 = due1.plusMonths(1);
        job.runFor(due2);
        job.runFor(due2);
        d = detail(s, loanId);
        assertThat(d.get("schedule").get(1).get("status").asString()).isEqualTo("OVERDUE");
        assertThat(notifications(s.userId(), "EMI_OVERDUE")).isEqualTo(1);

        // Money arrives: the next run collects the overdue instalment.
        Bank.deposit(this, s, account, "20000.00");
        job.runFor(due2.plusDays(1));
        d = detail(s, loanId);
        assertThat(d.get("schedule").get(1).get("status").asString()).isEqualTo("PAID");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void frozenAccountMakesTheEmiOverdue() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        String loanId = activeLoan(s, admin, "30000.00", 6);
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", Bank.firstAccountId(this, s));

        job.runFor(firstDueDate());
        assertThat(detail(s, loanId).get("schedule").get(0).get("status").asString()).isEqualTo("OVERDUE");
        Response blocked = repay(s, loanId, Bank.firstAccountId(this, s), null, UUID.randomUUID());
        assertThat(blocked.status()).isEqualTo(422);
        assertThat(blocked.jsonBody().get("code").asString()).isEqualTo("ACCOUNT_FROZEN");
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrentApprovalsDisburseExactlyOnce() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID account = Bank.firstAccountId(this, s);
        String loanId = apply(s, "40000.00", 12, "50000.00", account).jsonBody().get("id").asString();

        List<Integer> statuses = runConcurrently(8, () -> approve(admin, loanId).status());
        assertThat(statuses).containsOnly(200, 409);
        assertThat(statuses.stream().filter(x -> x == 200).count()).isEqualTo(1);
        assertThat(Bank.balance(this, s, account)).isEqualTo("40000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM loan_installments WHERE loan_id = ?::uuid", Integer.class,
                loanId)).isEqualTo(12);
    }

    @Test
    void concurrentRepaymentsNeverOverpay() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID account = Bank.firstAccountId(this, s);
        String loanId = activeLoan(s, admin, "60000.00", 6);
        Bank.deposit(this, s, account, "10000.00");

        List<Integer> statuses = runConcurrently(10, () -> repay(s, loanId, account, null, UUID.randomUUID()).status());
        assertThat(statuses.stream().filter(x -> x == 201).count()).isEqualTo(6);
        assertThat(statuses.stream().filter(x -> x == 409).count()).isEqualTo(4);

        JsonNode d = detail(s, loanId);
        assertThat(d.get("status").asString()).isEqualTo("CLOSED");
        BigDecimal totalPaid = jdbc.queryForObject("SELECT sum(amount) FROM loan_repayments WHERE loan_id = ?::uuid",
                BigDecimal.class, loanId);
        BigDecimal scheduled = jdbc.queryForObject("SELECT sum(emi) FROM loan_installments WHERE loan_id = ?::uuid",
                BigDecimal.class, loanId);
        assertThat(totalPaid).isEqualByComparingTo(scheduled);
        assertThat(new BigDecimal(Bank.balance(this, s, account)))
                .isEqualByComparingTo(new BigDecimal("70000.00").subtract(scheduled));
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    private static <T> List<T> runConcurrently(int n, Callable<T> task) throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(task));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
