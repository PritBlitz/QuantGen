package com.quantgen.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M3 part 3: categories (F-INS-1), recategorization, budgets and alerts (F-INS-2), analytics goals. */
class InsightsIT extends IntegrationTest {

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.BUSINESS_ZONE);
    }

    private Response pay(TestUsers.Session s, UUID from, String toNumber, String amount, String note) {
        Map<String, Object> body = new HashMap<>(Map.of("fromAccountId", from.toString(), "toAccountNumber", toNumber,
                "amount", amount));
        if (note != null) {
            body.put("note", note);
        }
        Response res = postJson("/api/v1/transfers", body, Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return res;
    }

    private Map<String, JsonNode> byKey(JsonNode rows) {
        Map<String, JsonNode> out = new HashMap<>();
        rows.forEach(r -> out.put(r.get("key").asString(), r));
        return out;
    }

    @Test
    void transactionsAreCategorizedAndSpendingAddsUp() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "20000.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);

        JsonNode lunch = pay(alice, savings, bobNumber, "500.00", "Lunch at the cafe").jsonBody();
        assertThat(lunch.get("category").asString()).isEqualTo("FOOD");
        pay(alice, savings, bobNumber, "3000.00", "Rent for October");
        pay(alice, savings, bobNumber, "200.00", null);
        JsonNode checking = postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice)).jsonBody();
        pay(alice, savings, checking.get("accountNumber").asString(), "1000.00", "food money"); // own: not spending
        String billId = postJson("/api/v1/billers/b1000000-0000-4000-8000-000000000001/fetch",
                Map.of("consumerNumber", "6666666666"), Bank.auth(alice)).jsonBody().get("billId").asString();
        BigDecimal bill = new BigDecimal(postJson("/api/v1/bill-payments", Map.of("billId", billId,
                "fromAccountId", savings.toString()), Bank.authWithKey(alice, UUID.randomUUID())).jsonBody()
                .get("amount").asString());

        Map<String, JsonNode> spending = byKey(get("/api/v1/insights/spending", alice.accessToken()).jsonBody());
        assertThat(spending.keySet()).containsExactlyInAnyOrder("FOOD", "RENT", "TRANSFERS", "BILLS");
        assertThat(spending.get("FOOD").get("amount").asString()).isEqualTo("500.00");
        assertThat(spending.get("FOOD").get("count").asLong()).isEqualTo(1);
        assertThat(spending.get("RENT").get("amount").asString()).isEqualTo("3000.00");
        assertThat(spending.get("TRANSFERS").get("amount").asString()).isEqualTo("200.00");
        assertThat(new BigDecimal(spending.get("BILLS").get("amount").asString())).isEqualByComparingTo(bill);
        JsonNode months = get("/api/v1/insights/spending?groupBy=month", alice.accessToken()).jsonBody();
        assertThat(months.size()).isEqualTo(1);
        assertThat(months.get(0).get("key").asString()).isEqualTo(today().toString().substring(0, 7));
        assertThat(new BigDecimal(months.get(0).get("amount").asString()))
                .isEqualByComparingTo(new BigDecimal("3700.00").add(bill));
        assertThat(get("/api/v1/insights/spending?groupBy=week", alice.accessToken()).status()).isEqualTo(400);

        // Analytics agree: the top category is the biggest spend.
        JsonNode summary = get("/api/v1/analytics/summary", alice.accessToken()).jsonBody();
        String top = bill.compareTo(new BigDecimal("3000.00")) > 0 ? "BILLS" : "RENT";
        assertThat(summary.get("topCategory").get("key").asString()).isEqualTo(top);
        assertThat(new BigDecimal(summary.get("spending").asString()))
                .isEqualByComparingTo(new BigDecimal("3700.00").add(bill));

        // Every transaction carries a category; the deposit is income, the own move a transfer.
        Map<String, String> byDescription = new HashMap<>();
        get("/api/v1/accounts/" + savings + "/transactions?size=50", alice.accessToken()).jsonBody().get("content")
                .forEach(t -> byDescription.put(t.get("description").asString(), t.get("category").asString()));
        assertThat(byDescription).containsEntry("Lunch at the cafe", "FOOD").containsEntry("food money", "TRANSFERS");
        assertThat(byDescription.values()).contains("INCOME", "BILLS");
        // The receiver sees the same payments as income.
        get("/api/v1/accounts/" + Bank.firstAccountId(this, bob) + "/transactions", bob.accessToken()).jsonBody()
                .get("content").forEach(t -> assertThat(t.get("category").asString()).isEqualTo("INCOME"));
        JsonNode transfers = get("/api/v1/transfers", alice.accessToken()).jsonBody().get("content");
        assertThat(transfers.get(transfers.size() - 1).get("category").asString()).isEqualTo("FOOD");
    }

    @Test
    void recategorizingAPaymentOrItsCounterpartySticks() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "5000.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);
        pay(alice, savings, bobNumber, "100.00", "Movie night");
        pay(alice, savings, bobNumber, "250.00", null);

        List<JsonNode> lines = new java.util.ArrayList<>();
        get("/api/v1/accounts/" + savings + "/transactions", alice.accessToken()).jsonBody().get("content")
                .forEach(lines::add);
        JsonNode plain = lines.stream().filter(t -> t.get("amount").asString().equals("250.00")).findFirst().orElseThrow();
        JsonNode movie = lines.stream().filter(t -> t.get("amount").asString().equals("100.00")).findFirst().orElseThrow();
        JsonNode deposit = lines.stream().filter(t -> t.get("type").asString().equals("DEPOSIT")).findFirst().orElseThrow();
        assertThat(movie.get("category").asString()).isEqualTo("ENTERTAINMENT");
        String plainPath = "/api/v1/transactions/" + plain.get("id").asString() + "/category";

        assertThat(patchJson(plainPath, Map.of("category", "PETS"), Bank.auth(alice)).status()).isEqualTo(400);
        assertThat(patchJson(plainPath, Map.of("category", "SHOPPING"), Bank.auth(bob)).status()).isEqualTo(404);
        assertThat(patchJson("/api/v1/transactions/" + deposit.get("id").asString() + "/category",
                Map.of("category", "OTHER", "applyToCounterparty", true), Bank.auth(alice)).status()).isEqualTo(400);

        JsonNode changed = patchJson(plainPath, Map.of("category", "SHOPPING"), Bank.auth(alice)).jsonBody();
        assertThat(changed.get("category").asString()).isEqualTo("SHOPPING");
        assertThat(changed.get("amount").asString()).isEqualTo("250.00");

        // "Always use HEALTH for Bob": every payment to Bob follows, the movie too, and future ones.
        JsonNode always = patchJson(plainPath, Map.of("category", "HEALTH", "applyToCounterparty", true),
                Bank.auth(alice)).jsonBody();
        assertThat(always.get("category").asString()).isEqualTo("HEALTH");
        pay(alice, savings, bobNumber, "40.00", "Pizza");
        Map<String, JsonNode> spending = byKey(get("/api/v1/insights/spending", alice.accessToken()).jsonBody());
        assertThat(spending.keySet()).containsExactly("HEALTH");
        assertThat(spending.get("HEALTH").get("amount").asString()).isEqualTo("390.00");
        // Bob's own view of these payments is unaffected by Alice's choices.
        get("/api/v1/accounts/" + Bank.firstAccountId(this, bob) + "/transactions", bob.accessToken()).jsonBody()
                .get("content").forEach(t -> assertThat(t.get("category").asString()).isEqualTo("INCOME"));
    }

    @Test
    void budgetsTrackSpendingAndAlertOncePerThreshold() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "5000.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);
        pay(alice, savings, bobNumber, "500.00", "dinner");

        assertThat(get("/api/v1/budgets", alice.accessToken()).jsonBody().size()).isZero();
        assertThat(put("/api/v1/budgets", List.of(Map.of("category", "INCOME", "monthlyLimit", "100.00")),
                alice.accessToken()).status()).isEqualTo(400);
        assertThat(put("/api/v1/budgets", List.of(Map.of("category", "FOOD", "monthlyLimit", "100.00"),
                Map.of("category", "FOOD", "monthlyLimit", "200.00")), alice.accessToken()).status()).isEqualTo(400);
        assertThat(put("/api/v1/budgets", List.of(Map.of("category", "FOOD", "monthlyLimit", "0.50")),
                alice.accessToken()).status()).isEqualTo(400);
        assertThat(put("/api/v1/budgets", List.of(Map.of("category", "FOOD", "monthlyLimit", "10.001")),
                alice.accessToken()).status()).isEqualTo(400);

        // 500 of 600 = 83.33%: the 80% alert goes out straight away.
        JsonNode budgets = put("/api/v1/budgets", List.of(Map.of("category", "FOOD", "monthlyLimit", "600.00"),
                Map.of("category", "TRAVEL", "monthlyLimit", "2000.00")), alice.accessToken()).jsonBody();
        assertThat(budgets.size()).isEqualTo(2);
        JsonNode food = budgets.get(0);
        assertThat(food.get("category").asString()).isEqualTo("FOOD");
        assertThat(food.get("spent").asString()).isEqualTo("500.00");
        assertThat(food.get("pct").asString()).isEqualTo("83.33");
        assertThat(budgets.get(1).get("spent").asString()).isEqualTo("0.00");
        assertThat(alerts(alice)).isEqualTo(1);

        pay(alice, savings, bobNumber, "150.00", "Swiggy order"); // 650 / 600: the 100% alert, via the outbox
        waitFor(() -> alerts(alice) == 2);
        pay(alice, savings, bobNumber, "10.00", "chai");         // still over: no new alert
        Thread.sleep(1500);
        assertThat(alerts(alice)).isEqualTo(2);
        assertThat(get("/api/v1/budgets", alice.accessToken()).jsonBody().get(0).get("pct").asString())
                .isEqualTo("110.00");

        assertThat(put("/api/v1/budgets", List.of(), alice.accessToken()).jsonBody().size()).isZero();
        assertThat(get("/api/v1/budgets", bob.accessToken()).jsonBody().size()).isZero();
    }

    @Test
    void goalProgressAndAdminCategoryBreakdown() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, savings, "5000.00");
        JsonNode goal = postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", "Laptop", "target", "1000.00"),
                Bank.auth(alice)).jsonBody();
        Map<String, Object> dated = new HashMap<>(Map.of("type", "GOAL", "name", "Soon", "target", "10000.00",
                "targetDate", today().plusDays(30).toString()));
        JsonNode soon = postJson("/api/v1/accounts", dated, Bank.auth(alice)).jsonBody();
        pay(alice, savings, goal.get("accountNumber").asString(), "300.00", null);
        pay(alice, savings, soon.get("accountNumber").asString(), "900.00", null);

        JsonNode goals = get("/api/v1/analytics/goals", alice.accessToken()).jsonBody();
        assertThat(goals.size()).isEqualTo(2);
        JsonNode laptop = goals.get(0);
        assertThat(laptop.get("name").asString()).isEqualTo("Laptop");
        assertThat(laptop.get("saved").asString()).isEqualTo("300.00");
        assertThat(laptop.get("pct").asString()).isEqualTo("30.00");
        assertThat(laptop.get("monthlyPace").asString()).isEqualTo("100.00");           // 300 over 90 days, per 30
        assertThat(laptop.get("projectedDate").asString()).isEqualTo(today().plusDays(210).toString()); // 700 / 100 × 30
        assertThat(laptop.get("onTrack").asBoolean()).isTrue();                            // no target date
        JsonNode late = goals.get(1);
        assertThat(late.get("monthlyPace").asString()).isEqualTo("300.00");
        assertThat(late.get("onTrack").asBoolean()).isFalse(); // 9100 more at 300 a month won't make it in 30 days

        TestUsers.Session admin = Bank.admin(this);
        Response breakdown = get("/api/v1/admin/analytics/breakdown?dimension=SPEND_CATEGORY", admin.accessToken());
        assertThat(breakdown.status()).isEqualTo(200);
    }

    private int alerts(TestUsers.Session s) {
        int n = 0;
        for (JsonNode notification : get("/api/v1/notifications", s.accessToken()).jsonBody()) {
            if ("BUDGET_ALERT".equals(notification.get("type").asString())) {
                n++;
            }
        }
        return n;
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("condition not met in time");
    }
}
