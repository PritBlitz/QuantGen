package com.quantgen.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-MA: admin and customer analytics agree on a fixture, suppress small cells, audit exports and stay in scope. */
class AnalyticsIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    PortfolioSnapshotJob snapshots;

    private final String today = LocalDate.now(TimeConfig.BUSINESS_ZONE).toString();

    // ------------------------------------------------------------------ the shared definition

    @Test
    void adminAndCustomerNumbersAgreeOnAFixture() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        JsonNode before = overview(admin);

        Bank.deposit(this, alice, aAcc, "10000.00");
        Bank.deposit(this, bob, bAcc, "500.00");
        assertThat(Bank.transfer(this, alice, aAcc, Bank.firstAccountNumber(this, bob), "1500.00", UUID.randomUUID())
                .status()).isEqualTo(201);
        String checking = postJson("/api/v1/accounts", Map.of("type", "CHECKING"), Bank.auth(alice))
                .jsonBody().get("accountNumber").asString();
        assertThat(Bank.transfer(this, alice, aAcc, checking, "700.00", UUID.randomUUID()).status()).isEqualTo(201);
        BigDecimal bought = trade(alice, aAcc, "BUY", 3);
        BigDecimal sold = trade(alice, aAcc, "SELL", 1);

        JsonNode after = overview(admin);
        // Admin deltas.
        assertThat(delta(before, after, "NET_INFLOW")).isEqualByComparingTo("10500.00");
        assertThat(delta(before, after, "TRANSFER_VOLUME")).isEqualByComparingTo("1500.00"); // own-account move excluded
        assertThat(delta(before, after, "TRANSFER_COUNT")).isEqualByComparingTo("1");
        assertThat(delta(before, after, "TRADE_VOLUME")).isEqualByComparingTo(bought.add(sold));
        assertThat(delta(before, after, "TRADE_COUNT")).isEqualByComparingTo("2");

        // Customer view of the same events.
        JsonNode a = get("/api/v1/analytics/summary?from=" + today + "&to=" + today, alice.accessToken()).jsonBody();
        JsonNode b = get("/api/v1/analytics/summary?from=" + today + "&to=" + today, bob.accessToken()).jsonBody();
        assertThat(a.get("income").asString()).isEqualTo("10000.00");
        assertThat(a.get("spending").asString()).isEqualTo("1500.00");
        assertThat(a.get("net").asString()).isEqualTo("8500.00");
        assertThat(a.get("savingsRatePct").asString()).isEqualTo("85.00");
        assertThat(b.get("income").asString()).isEqualTo("2000.00"); // 500 deposit + 1500 received
        assertThat(b.get("spending").asString()).isEqualTo("0.00");
        assertThat(a.get("topCategory").get("key").asString()).isEqualTo("TRANSFERS");
        assertThat(a.get("largestSpend").get("amount").asString()).isEqualTo("1500.00");

        // The two sides add up: what customers spent on transfers is what admin counts as transfer volume,
        // and customer income less transfers between users is what came in from outside.
        BigDecimal income = new BigDecimal(a.get("income").asString()).add(new BigDecimal(b.get("income").asString()));
        BigDecimal spending = new BigDecimal(a.get("spending").asString()).add(new BigDecimal(b.get("spending").asString()));
        assertThat(spending).isEqualByComparingTo(delta(before, after, "TRANSFER_VOLUME"));
        assertThat(income.subtract(spending)).isEqualByComparingTo(delta(before, after, "NET_INFLOW"));

        JsonNode cashflow = get("/api/v1/analytics/cashflow?months=3", alice.accessToken()).jsonBody().get("points");
        assertThat(cashflow.size()).isEqualTo(3);
        JsonNode thisMonth = cashflow.get(2);
        assertThat(thisMonth.get("income").asString()).isEqualTo("10000.00");
        assertThat(thisMonth.get("spending").asString()).isEqualTo("1500.00");
        assertThat(thisMonth.get("invested").asString()).isEqualTo(Money2(bought));
        assertThat(cashflow.get(0).get("income").asString()).isEqualTo("0.00");
    }

    @Test
    void aReversedTransferCancelsOutOfSpendingAndIncome() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, aAcc, "3000.00");
        Bank.transfer(this, alice, aAcc, Bank.firstAccountNumber(this, bob), "1000.00", UUID.randomUUID());
        UUID entry = jdbc.queryForObject("SELECT journal_entry_id FROM transfers WHERE user_id = ?", UUID.class,
                alice.userId());

        tx.executeWithoutResult(st -> ledger.reverse(entry, "test reversal", null));

        JsonNode a = get("/api/v1/analytics/summary?from=" + today + "&to=" + today, alice.accessToken()).jsonBody();
        JsonNode b = get("/api/v1/analytics/summary?from=" + today + "&to=" + today, bob.accessToken()).jsonBody();
        assertThat(a.get("spending").asString()).isEqualTo("0.00");
        assertThat(a.get("income").asString()).isEqualTo("3000.00");
        assertThat(b.get("income").asString()).isEqualTo("0.00");
    }

    // ------------------------------------------------------------------ privacy

    @Test
    void smallCellsAreSuppressedAndLargeOnesShown() {
        TestUsers.Session admin = Bank.admin(this);
        String lonely = "Zanzibar " + UUID.randomUUID().toString().substring(0, 8);
        String shared = "Sharedpurpose " + UUID.randomUUID().toString().substring(0, 8);
        applyLoan(TestUsers.registerAndLogin(this), lonely);
        for (int i = 0; i < 5; i++) {
            applyLoan(TestUsers.registerAndLogin(this), shared);
        }

        Response res = get("/api/v1/admin/analytics/breakdown?dimension=LOAN_PURPOSE&limit=50&from=" + today
                + "&to=" + today, admin.accessToken());
        assertThat(res.status()).isEqualTo(200);
        JsonNode rows = res.jsonBody().get("rows");
        // The one-user purpose never appears, in a key, a label or anywhere else in the body.
        assertThat(res.body().toLowerCase()).doesNotContain("zanzibar");
        JsonNode sharedRow = null;
        boolean anySuppressed = false;
        for (JsonNode r : rows) {
            if (r.get("suppressed").asBoolean()) {
                anySuppressed = true;
                assertThat(r.get("value").isNull()).isTrue();
                assertThat(r.get("count").isNull()).isTrue();
                assertThat(r.get("sharePct").isNull()).isTrue();
            } else if (r.get("key").asString().equals(shared.toLowerCase())) {
                sharedRow = r;
            }
        }
        assertThat(anySuppressed).isTrue();
        assertThat(sharedRow).isNotNull();
        assertThat(sharedRow.get("count").asLong()).isEqualTo(5);
        assertThat(sharedRow.get("value").asString()).isEqualTo("50000.00");
    }

    @Test
    void adminResponsesCarryNoPersonalData() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        Bank.deposit(this, alice, Bank.firstAccountId(this, alice), "100.00");
        String accountNumber = Bank.firstAccountNumber(this, alice);

        for (String path : new String[]{"overview", "funnel", "cohorts", "loan-book", "adoption", "health",
                "breakdown?dimension=TRANSACTION_TYPE", "breakdown?dimension=ACCOUNT_TYPE",
                "timeseries?metric=ACTIVE_USERS"}) {
            Response res = get("/api/v1/admin/analytics/" + path, admin.accessToken());
            assertThat(res.status()).as(path).isEqualTo(200);
            assertThat(res.body()).as(path).doesNotContain(alice.email()).doesNotContain(accountNumber)
                    .doesNotContain("Test User");
        }
    }

    @Test
    void onlyAdminsReachBusinessAnalyticsAndCustomersOnlySeeTheirOwn() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        assertThat(get("/api/v1/admin/analytics/overview").status()).isEqualTo(401);
        assertThat(get("/api/v1/admin/analytics/overview", alice.accessToken()).status()).isEqualTo(403);
        assertThat(get("/api/v1/admin/analytics/export?report=overview", alice.accessToken()).status()).isEqualTo(403);
        assertThat(get("/api/v1/analytics/summary").status()).isEqualTo(401);

        Bank.deposit(this, alice, Bank.firstAccountId(this, alice), "777.00");
        JsonNode bobsView = get("/api/v1/analytics/summary?from=" + today + "&to=" + today, bob.accessToken()).jsonBody();
        assertThat(bobsView.get("income").asString()).isEqualTo("0.00");
        // Bob cannot read Alice's account history, nor price her loan.
        UUID aliceAccount = Bank.firstAccountId(this, alice);
        assertThat(get("/api/v1/analytics/balance-history?accountId=" + aliceAccount, bob.accessToken()).status())
                .isEqualTo(404);
        assertThat(postJson("/api/v1/analytics/loans/" + UUID.randomUUID() + "/prepayment", Map.of("amount", "1000.00"),
                Bank.auth(bob)).status()).isEqualTo(404);
    }

    // ------------------------------------------------------------------ export (F-ANL-9)

    @Test
    void everyExportIsAuditedAndSafeToOpenInASpreadsheet() {
        TestUsers.Session admin = Bank.admin(this);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ANALYTICS_EXPORTED'",
                Integer.class);

        Response csv = get("/api/v1/admin/analytics/export?report=overview&from=" + today + "&to=" + today,
                admin.accessToken());

        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.raw().headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        assertThat(csv.raw().headers().firstValue("Content-Disposition").orElse("")).contains("attachment")
                .contains("quantgen-overview-");
        assertThat(csv.body()).startsWith("key,label,unit,value,previous,changePct\r\n").contains("TOTAL_USERS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ANALYTICS_EXPORTED'",
                Integer.class)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT details FROM audit_logs WHERE action = 'ANALYTICS_EXPORTED' "
                + "ORDER BY created_at DESC LIMIT 1", String.class)).contains("from=" + today);

        // A failed export (bad report) is not recorded as a successful one.
        assertThat(get("/api/v1/admin/analytics/export?report=nope", admin.accessToken()).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ANALYTICS_EXPORTED'",
                Integer.class)).isEqualTo(before + 1);

        for (String report : new String[]{"funnel", "cohorts", "loan-book", "breakdown&dimension=ACCOUNT_TYPE",
                "timeseries&metric=NEW_USERS"}) {
            assertThat(get("/api/v1/admin/analytics/export?report=" + report, admin.accessToken()).status())
                    .as(report).isEqualTo(200);
        }
    }

    @Test
    void csvCellsThatLookLikeFormulasAreNeutralised() {
        assertThat(Csv.escape("=HYPERLINK(\"x\")")).isEqualTo("\"'=HYPERLINK(\"\"x\"\")\"");
        assertThat(Csv.escape("+1+1")).isEqualTo("'+1+1");
        assertThat(Csv.escape("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(Csv.escape("-12.50")).isEqualTo("-12.50"); // a negative number is data
        assertThat(Csv.escape("a,b")).isEqualTo("\"a,b\"");
    }

    // ------------------------------------------------------------------ series, breakdowns, ranges

    @Test
    void timeseriesBucketsFillGapsCompareAndMarkUnavailableMetrics() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.registerAndLogin(this);

        JsonNode daily = get("/api/v1/admin/analytics/timeseries?metric=NEW_USERS&granularity=DAY&compare=true&from="
                + LocalDate.parse(today).minusDays(6) + "&to=" + today, admin.accessToken()).jsonBody();
        assertThat(daily.get("available").asBoolean()).isTrue();
        assertThat(daily.get("points").size()).isEqualTo(7);
        assertThat(daily.get("previous").size()).isEqualTo(7);
        assertThat(daily.get("points").get(6).get("t").asString()).isEqualTo(today);
        assertThat(Integer.parseInt(daily.get("points").get(6).get("value").asString())).isGreaterThanOrEqualTo(1);
        assertThat(daily.get("unit").asString()).isEqualTo("COUNT");

        JsonNode weekly = get("/api/v1/admin/analytics/timeseries?metric=TRANSFER_VOLUME&granularity=WEEK&from="
                + LocalDate.parse(today).minusDays(20) + "&to=" + today, admin.accessToken()).jsonBody();
        for (JsonNode p : weekly.get("points")) {
            assertThat(LocalDate.parse(p.get("t").asString()).getDayOfWeek().getValue()).isEqualTo(1); // Mondays
        }

        JsonNode deposits = get("/api/v1/admin/analytics/timeseries?metric=CUSTOMER_DEPOSITS&from="
                + LocalDate.parse(today).minusDays(2) + "&to=" + today, admin.accessToken()).jsonBody();
        assertThat(new BigDecimal(deposits.get("points").get(2).get("value").asString()))
                .isGreaterThanOrEqualTo(new BigDecimal(deposits.get("points").get(0).get("value").asString()));

        JsonNode soon = get("/api/v1/admin/analytics/timeseries?metric=AI_COST_USD", admin.accessToken()).jsonBody();
        assertThat(soon.get("available").asBoolean()).isFalse();
        assertThat(soon.get("points").size()).isZero();

        assertThat(get("/api/v1/admin/analytics/timeseries?metric=OVERDUE_RATE_PCT", admin.accessToken()).status())
                .isEqualTo(400);
        assertThat(get("/api/v1/admin/analytics/timeseries?metric=NOPE", admin.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/admin/analytics/timeseries?metric=NEW_USERS&granularity=YEAR", admin.accessToken())
                .status()).isEqualTo(400);
    }

    @Test
    void overviewShapeAndPreviousPeriodAreRight() {
        TestUsers.Session admin = Bank.admin(this);

        JsonNode o = get("/api/v1/admin/analytics/overview?from=" + LocalDate.parse(today).minusDays(9) + "&to=" + today,
                admin.accessToken()).jsonBody();

        assertThat(o.get("previousTo").asString()).isEqualTo(LocalDate.parse(today).minusDays(10).toString());
        assertThat(o.get("previousFrom").asString()).isEqualTo(LocalDate.parse(today).minusDays(19).toString());
        assertThat(o.get("kpis").size()).isEqualTo(14);
        for (JsonNode k : o.get("kpis")) {
            assertThat(k.get("value").isString()).as(k.get("key").asString()).isTrue();
            assertThat(k.get("previous").isString()).isTrue();
            assertThat(k.has("changePct")).isTrue();
            assertThat(k.get("goodDirection").asString()).isIn("UP", "DOWN");
            assertThat(k.get("unit").asString()).isIn("MONEY", "COUNT", "PERCENT");
        }
        // Unused metrics report 0 for an empty previous period and changePct null, never a divide-by-zero.
        JsonNode empty = get("/api/v1/admin/analytics/overview?from=2001-01-10&to=2001-01-12", admin.accessToken())
                .jsonBody();
        for (JsonNode k : empty.get("kpis")) {
            if (k.get("previous").asString().matches("0(\\.00)?")) {
                assertThat(k.get("changePct").isNull()).isTrue();
            }
        }
    }

    @Test
    void rangesAreValidated() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session user = TestUsers.registerAndLogin(this);

        assertThat(get("/api/v1/admin/analytics/overview?from=2026-02-01&to=2026-01-01", admin.accessToken()).status())
                .isEqualTo(400);
        assertThat(get("/api/v1/analytics/summary?from=2024-01-01&to=2026-01-01", user.accessToken()).status())
                .isEqualTo(400); // > 366 days for a customer
        assertThat(get("/api/v1/admin/analytics/overview?from=2024-01-01&to=2026-01-01", admin.accessToken()).status())
                .isEqualTo(200); // admins may look at up to 3 years
        assertThat(get("/api/v1/admin/analytics/overview?from=2020-01-01&to=2026-01-01", admin.accessToken()).status())
                .isEqualTo(400);
        assertThat(get("/api/v1/analytics/summary?from=not-a-date", user.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/analytics/cashflow?months=25", user.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/admin/analytics/cohorts?months=13", admin.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/analytics/balance-history?range=2Y", user.accessToken()).status()).isEqualTo(400);
    }

    @Test
    void funnelCohortsLoanBookAdoptionAndHealthAnswer() {
        TestUsers.Session admin = Bank.admin(this);
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "5000.00");
        Bank.ensurePin(this, alice);

        JsonNode funnel = get("/api/v1/admin/analytics/funnel?from=" + today + "&to=" + today, admin.accessToken())
                .jsonBody().get("steps");
        assertThat(funnel.size()).isEqualTo(5);
        long previous = Long.MAX_VALUE;
        for (JsonNode s : funnel) {
            long users = Long.parseLong(s.get("users").asString());
            assertThat(users).isLessThanOrEqualTo(previous); // a funnel only narrows
            previous = users;
        }
        assertThat(funnel.get(0).get("conversionPct").asString()).isEqualTo("100.00");

        JsonNode cohorts = get("/api/v1/admin/analytics/cohorts?months=3", admin.accessToken()).jsonBody().get("cohorts");
        assertThat(cohorts.size()).isEqualTo(3);
        assertThat(cohorts.get(0).get("retentionPct").size()).isEqualTo(3);
        assertThat(cohorts.get(2).get("retentionPct").get(1).isNull()).isTrue(); // next month has not happened

        JsonNode book = get("/api/v1/admin/analytics/loan-book", admin.accessToken()).jsonBody();
        assertThat(book.get("ageing").size()).isEqualTo(4);
        assertThat(book.get("ageing").get(0).get("bucket").asString()).isEqualTo("1-30");

        JsonNode adoption = get("/api/v1/admin/analytics/adoption?from=" + today + "&to=" + today, admin.accessToken())
                .jsonBody();
        boolean handleUnavailable = false;
        boolean pinAvailable = false;
        for (JsonNode f : adoption.get("features")) {
            handleUnavailable |= f.get("key").asString().equals("HANDLE") && !f.get("available").asBoolean();
            pinAvailable |= f.get("key").asString().equals("PIN") && f.get("available").asBoolean()
                    && Integer.parseInt(f.get("users").asString()) >= 1;
        }
        assertThat(handleUnavailable).isTrue();
        assertThat(pinAvailable).isTrue();
        assertThat(adoption.get("ai").get("available").asBoolean()).isFalse();

        JsonNode health = get("/api/v1/admin/analytics/health", admin.accessToken()).jsonBody();
        assertThat(health.get("reconcileBalanced").asBoolean()).isTrue();
        assertThat(health.get("outboxLag").asString()).matches("\\d+");
        assertThat(health.get("moneyErrorRatePct").asString()).matches("\\d+\\.\\d\\d");
    }

    @Test
    void failedMoneyRequestsAreRecordedWithoutAmountsOrNumbers() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        Integer before = jdbc.queryForObject("SELECT count(*) FROM request_failures WHERE code = 'INSUFFICIENT_FUNDS'",
                Integer.class);

        Response res = Bank.transfer(this, alice, Bank.firstAccountId(this, alice),
                Bank.firstAccountNumber(this, bob), "999.00", UUID.randomUUID());

        assertThat(res.status()).isEqualTo(422);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM request_failures WHERE code = 'INSUFFICIENT_FUNDS'",
                Integer.class)).isEqualTo(before + 1);
        Map<String, Object> row = jdbc.queryForMap("SELECT endpoint, status, user_id FROM request_failures "
                + "WHERE code = 'INSUFFICIENT_FUNDS' ORDER BY created_at DESC LIMIT 1");
        assertThat(row.get("endpoint")).isEqualTo("POST /transfers");
        assertThat(row.get("user_id")).isEqualTo(alice.userId());
        // A prompt for step-up is not a failure.
        Integer stepUps = jdbc.queryForObject("SELECT count(*) FROM request_failures WHERE code = 'STEP_UP_REQUIRED'",
                Integer.class);
        assertThat(stepUps).isZero();
    }

    // ------------------------------------------------------------------ customer detail

    @Test
    void balanceHistoryEndsAtTheCurrentBalanceAndCounterpartiesAreMasked() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, aAcc, "2000.00");
        Bank.transfer(this, alice, aAcc, Bank.firstAccountNumber(this, bob), "300.00", UUID.randomUUID());

        JsonNode h = get("/api/v1/analytics/balance-history?range=1M", alice.accessToken()).jsonBody();
        assertThat(h.get("accountId").isNull()).isTrue();
        assertThat(h.get("points").size()).isEqualTo(30);
        assertThat(h.get("points").get(29).get("t").asString()).isEqualTo(today);
        assertThat(h.get("points").get(29).get("balance").asString()).isEqualTo("1700.00");
        assertThat(h.get("points").get(0).get("balance").asString()).isEqualTo("0.00");
        JsonNode one = get("/api/v1/analytics/balance-history?range=3M&accountId=" + aAcc, alice.accessToken()).jsonBody();
        assertThat(one.get("points").size()).isEqualTo(90);
        assertThat(one.get("points").get(89).get("balance").asString()).isEqualTo(Bank.balance(this, alice, aAcc));

        JsonNode cp = get("/api/v1/analytics/counterparties?from=" + today + "&to=" + today, alice.accessToken()).jsonBody();
        assertThat(cp.size()).isEqualTo(1);
        assertThat(cp.get(0).get("label").asString()).isEqualTo("Test U.");
        assertThat(cp.get(0).get("accountNumberMasked").asString()).startsWith("••••")
                .doesNotContain(Bank.firstAccountNumber(this, bob));
        assertThat(cp.get(0).get("sent").asString()).isEqualTo("300.00");
        assertThat(cp.get(0).get("received").asString()).isEqualTo("0.00");
        assertThat(cp.get(0).get("count").asLong()).isEqualTo(1);
        JsonNode bobsSide = get("/api/v1/analytics/counterparties?from=" + today + "&to=" + today, bob.accessToken()).jsonBody();
        assertThat(bobsSide.get(0).get("received").asString()).isEqualTo("300.00");
    }

    @Test
    void portfolioShowsHoldingsAllocationAndSnapshots() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "100000.00");
        assertThat(get("/api/v1/analytics/portfolio", alice.accessToken()).jsonBody().get("holdings").size()).isZero();

        trade(alice, acc, "BUY", 5);
        int written = snapshots.snapshot(LocalDate.parse(today));
        assertThat(written).isGreaterThanOrEqualTo(1);
        assertThat(snapshots.snapshot(LocalDate.parse(today))).isEqualTo(written); // idempotent

        JsonNode p = get("/api/v1/analytics/portfolio?range=1M", alice.accessToken()).jsonBody();
        assertThat(p.get("holdings").size()).isEqualTo(1);
        assertThat(p.get("allocation").size()).isEqualTo(1);
        assertThat(p.get("allocation").get(0).get("pct").asString()).isEqualTo("100.00");
        assertThat(p.get("points").size()).isGreaterThanOrEqualTo(1);
        assertThat(p.get("best").get("symbol").asString()).isEqualTo(p.get("worst").get("symbol").asString());
        assertThat(p.get("twrPct").isString()).isTrue();
        assertThat(get("/api/v1/analytics/portfolio?range=9Y", alice.accessToken()).status()).isEqualTo(400);
    }

    @Test
    void loanAnalyticsAndPrepaymentWhatIf() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);
        UUID acc = Bank.firstAccountId(this, s);
        String loanId = postJson("/api/v1/loans", Map.of("amount", "60000.00", "tenureMonths", 12,
                "monthlyIncome", "50000.00", "purpose", "Renovation", "disburseAccountId", acc.toString()), Bank.auth(s))
                .jsonBody().get("id").asString();

        // Not active yet: a pending loan cannot be prepaid.
        assertThat(postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "1000.00"),
                Bank.auth(s)).status()).isEqualTo(409);

        assertThat(postJson("/api/v1/admin/loans/" + loanId + "/approve", Map.of("reason", "Meets criteria"),
                Bank.auth(admin)).status()).isEqualTo(200);
        Map<String, Object> repay = new HashMap<>();
        repay.put("accountId", acc.toString());
        assertThat(postJson("/api/v1/loans/" + loanId + "/repay", repay, Bank.authWithKey(s, UUID.randomUUID()))
                .status()).isEqualTo(201);

        JsonNode loans = get("/api/v1/analytics/loans", s.accessToken()).jsonBody();
        assertThat(loans.get("loans").size()).isEqualTo(1);
        JsonNode l = loans.get("loans").get(0);
        assertThat(l.get("loanId").asString()).isEqualTo(loanId);
        assertThat(new BigDecimal(l.get("principalPaid").asString())).isPositive();
        assertThat(new BigDecimal(l.get("interestPaid").asString())).isPositive();
        assertThat(loans.get("totalInterestPaid").asString()).isEqualTo(l.get("interestPaid").asString());
        assertThat(l.get("projectedPayoffDate").isNull()).isFalse();
        assertThat(get("/api/v1/analytics/loans", other.accessToken()).jsonBody().get("loans").size()).isZero();

        JsonNode what = postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "20000.00"),
                Bank.auth(s)).jsonBody();
        assertThat(new BigDecimal(what.get("interestSaved").asString())).isPositive();
        assertThat(what.get("monthsSaved").asInt()).isGreaterThan(0);
        JsonNode bigger = postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "40000.00"),
                Bank.auth(s)).jsonBody();
        assertThat(new BigDecimal(bigger.get("interestSaved").asString()))
                .isGreaterThan(new BigDecimal(what.get("interestSaved").asString()));
        JsonNode all = postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "1000000.00"),
                Bank.auth(s)).jsonBody();
        assertThat(all.get("newPayoffDate").asString()).isEqualTo(today);

        // It is a calculation only: nothing moved, and nobody else can ask about this loan.
        assertThat(postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "20000.00"),
                Bank.auth(other)).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/analytics/loans/" + loanId + "/prepayment", Map.of("amount", "0.00"),
                Bank.auth(s)).status()).isEqualTo(400);
        assertThat(ledger.reconcile().balanced()).isTrue();
        assertThat(get("/api/v1/analytics/goals", s.accessToken()).jsonBody().size()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode overview(TestUsers.Session admin) {
        return get("/api/v1/admin/analytics/overview?from=" + today + "&to=" + today, admin.accessToken()).jsonBody();
    }

    private static BigDecimal kpi(JsonNode overview, String key) {
        for (JsonNode k : overview.get("kpis")) {
            if (k.get("key").asString().equals(key)) {
                return new BigDecimal(k.get("value").asString());
            }
        }
        throw new AssertionError("no KPI " + key);
    }

    private static BigDecimal delta(JsonNode before, JsonNode after, String key) {
        return kpi(after, key).subtract(kpi(before, key));
    }

    private static String Money2(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString();
    }

    /** Places a market order and returns its total. */
    private BigDecimal trade(TestUsers.Session s, UUID account, String side, int qty) {
        String symbol = get("/api/v1/market/instruments", s.accessToken()).jsonBody().get(0).get("symbol").asString();
        Response res = postJson("/api/v1/orders", Map.of("accountId", account.toString(), "symbol", symbol,
                "side", side, "quantity", qty), Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
        return new BigDecimal(res.jsonBody().get("total").asString());
    }

    private void applyLoan(TestUsers.Session s, String purpose) {
        Response res = postJson("/api/v1/loans", Map.of("amount", "10000.00", "tenureMonths", 6,
                "monthlyIncome", "100000.00", "purpose", purpose,
                "disburseAccountId", Bank.firstAccountId(this, s).toString()), Bank.auth(s));
        assertThat(res.status()).as(res.body()).isEqualTo(201);
    }
}
