package com.quantgen.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M3 part 4: net worth (F-INS-4) and the cash-flow forecast (F-INS-3). */
class PlanningIT extends IntegrationTest {

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.BUSINESS_ZONE);
    }

    private String activeLoan(TestUsers.Session s, String amount) {
        Response applied = postJson("/api/v1/loans", Map.of("amount", amount, "tenureMonths", 12, "monthlyIncome",
                "200000.00", "purpose", "Home renovation", "disburseAccountId", Bank.firstAccountId(this, s).toString()),
                Bank.auth(s));
        assertThat(applied.status()).as(applied.body()).isEqualTo(201);
        String id = applied.jsonBody().get("id").asString();
        Response approved = postJson("/api/v1/admin/loans/" + id + "/approve", Map.of("reason", "Meets criteria"),
                Bank.auth(Bank.admin(this)));
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        return id;
    }

    private void book(TestUsers.Session s, UUID from, String amount, int days) {
        Response res = postJson("/api/v1/fixed-deposits", Map.of("fromAccountId", from.toString(), "amount", amount,
                "tenureDays", days, "onMaturity", "PAYOUT"), Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
    }

    private void schedule(TestUsers.Session s, UUID from, String to, String amount, String frequency, LocalDate start) {
        Response res = postJson("/api/v1/scheduled-transfers", new HashMap<>(Map.of("fromAccountId", from.toString(),
                "toAccountNumber", to, "amount", amount, "frequency", frequency, "startDate", start.toString())),
                Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
    }

    @Test
    void netWorthAddsUpAssetsMinusLoans() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "10000.00");
        activeLoan(alice, "50000.00");
        JsonNode goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Car", "target", "500000.00"),
                Bank.auth(alice)).jsonBody();
        Bank.transfer(this, alice, savings, goal.get("accountNumber").asString(), "2000.00", UUID.randomUUID());
        book(alice, savings, "3000.00", 365);

        JsonNode nw = get("/api/v1/insights/net-worth", alice.accessToken()).jsonBody();
        JsonNode b = nw.get("breakdown");
        assertThat(b.get("cash").asString()).isEqualTo("55000.00");
        assertThat(b.get("goals").asString()).isEqualTo("2000.00");
        assertThat(b.get("fixedDeposits").asString()).isEqualTo("3000.00");
        assertThat(b.get("investments").asString()).isEqualTo("0.00");
        assertThat(b.get("loans").asString()).isEqualTo("50000.00");
        assertThat(nw.get("current").asString()).isEqualTo("10000.00");
        JsonNode history = nw.get("history");
        assertThat(history.size()).isEqualTo(12);
        assertThat(history.get(11).get("month").asString()).isEqualTo(today().toString().substring(0, 7));
        assertThat(history.get(11).get("value").asString()).isEqualTo("10000.00");
        assertThat(history.get(10).get("value").asString()).isEqualTo("0.00"); // she joined this month
        assertThat(get("/api/v1/insights/net-worth?months=3", alice.accessToken()).jsonBody().get("history").size())
                .isEqualTo(3);
        assertThat(get("/api/v1/insights/net-worth?months=0", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/insights/net-worth?months=25", alice.accessToken()).status()).isEqualTo(400);
    }

    @Test
    void forecastProjectsScheduledItemsAndWarns() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        String bobNumber = Bank.firstAccountNumber(this, bob);
        Bank.deposit(this, alice, savings, "3000.00");
        book(alice, savings, "1000.00", 7);                                       // pays back 1000.67 on day 7
        schedule(alice, savings, bobNumber, "1500.00", "MONTHLY", today().plusDays(1));
        schedule(alice, savings, bobNumber, "2000.00", "ONCE", today().plusDays(10));

        JsonNode f = get("/api/v1/insights/forecast?days=20", alice.accessToken()).jsonBody();
        JsonNode points = f.get("points");
        assertThat(points.size()).isEqualTo(21);
        assertThat(points.get(0).get("date").asString()).isEqualTo(today().toString());
        assertThat(points.get(0).get("balance").asString()).isEqualTo("2000.00");
        assertThat(points.get(1).get("balance").asString()).isEqualTo("500.00");
        assertThat(points.get(6).get("balance").asString()).isEqualTo("500.00");
        assertThat(points.get(7).get("balance").asString()).isEqualTo("1500.67"); // 1000 × 3.5 × 7 / 36500 = 0.67
        assertThat(points.get(10).get("balance").asString()).isEqualTo("-499.33");
        assertThat(points.get(20).get("balance").asString()).isEqualTo("-499.33");

        JsonNode warnings = f.get("warnings");
        assertThat(warnings.size()).isEqualTo(2);
        assertThat(warnings.get(0).get("code").asString()).isEqualTo("LOW_BALANCE");
        assertThat(warnings.get(0).get("date").asString()).isEqualTo(today().plusDays(1).toString());
        assertThat(warnings.get(1).get("code").asString()).isEqualTo("SCHEDULED_AT_RISK");
        assertThat(warnings.get(1).get("date").asString()).isEqualTo(today().plusDays(10).toString());

        // Bob sees Alice's monthly payment as money coming in.
        Bank.deposit(this, bob, Bank.firstAccountId(this, bob), "5000.00");
        JsonNode bobs = get("/api/v1/insights/forecast?days=10", bob.accessToken()).jsonBody().get("points");
        assertThat(bobs.get(1).get("balance").asString()).isEqualTo("6500.00");
        assertThat(bobs.get(10).get("balance").asString()).isEqualTo("8500.00");

        assertThat(get("/api/v1/insights/forecast?days=0", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/insights/forecast?days=91", alice.accessToken()).status()).isEqualTo(400);
    }

    @Test
    void forecastIncludesEmisAndFlagsOnesAtRisk() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        String loanId = activeLoan(alice, "60000.00");
        Map<String, Object> first = jdbc.queryForMap("SELECT due_date, emi FROM loan_installments WHERE loan_id = ?::uuid "
                + "AND installment_no = 1", loanId);
        LocalDate due = ((java.sql.Date) first.get("due_date")).toLocalDate();
        BigDecimal emi = (BigDecimal) first.get("emi");
        int days = (int) java.time.temporal.ChronoUnit.DAYS.between(today(), due);

        JsonNode points = get("/api/v1/insights/forecast?days=" + (days + 1), alice.accessToken()).jsonBody()
                .get("points");
        assertThat(new BigDecimal(points.get(days).get("balance").asString()))
                .isEqualByComparingTo(new BigDecimal("60000.00").subtract(emi));

        // Spend almost everything: the first EMI is now at risk.
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        Bank.transferStepUp(this, alice, Bank.firstAccountId(this, alice), Bank.firstAccountNumber(this, bob),
                "59900.00", UUID.randomUUID());
        JsonNode warnings = get("/api/v1/insights/forecast?days=" + (days + 1), alice.accessToken()).jsonBody()
                .get("warnings");
        assertThat(warnings.toString()).contains("EMI_AT_RISK").contains("LOW_BALANCE");
    }
}
