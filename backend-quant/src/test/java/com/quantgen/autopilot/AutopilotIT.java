package com.quantgen.autopilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.quantgen.common.events.OutboxEvent;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;
import com.quantgen.trading.service.PricesUpdatedEvent;

import tools.jackson.databind.JsonNode;

/** V2-M5 Autopilot core: validation, rendering, replay, and the deterministic engine. */
class AutopilotIT extends IntegrationTest {

    @Autowired
    AutopilotEngine engine;

    @Autowired
    LedgerService ledger;

    @Autowired
    ApplicationEventPublisher events;

    @BeforeEach
    void autopilotOn() {
        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'autopilot'");
    }

    @AfterEach
    void autopilotOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'autopilot'");
    }

    // ------------------------------------------------------------------ helpers

    private String goal(TestUsers.Session s, String name) {
        return postJson("/api/v1/accounts", Map.of("type", "GOAL", "name", name, "target", "100000.00"), Bank.auth(s))
                .jsonBody().get("id").asString();
    }

    private Response activate(TestUsers.Session s, String name, Map<String, Object> rule) {
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "RULE_ACTIVATE"));
        return postJson("/api/v1/autopilot/rules", Map.of("name", name, "rule", rule), h);
    }

    private static Map<String, Object> rule(Map<String, Object> trigger, List<Map<String, Object>> actions,
                                            String perRun, String perMonth) {
        return Map.of("dslVersion", 1, "trigger", trigger, "conditions", List.of(), "actions", actions,
                "caps", Map.of("perRun", perRun, "perMonth", perMonth));
    }

    private static Map<String, Object> transfer(String from, String to, String mode, String value) {
        return Map.of("type", "TRANSFER", "fromAccountId", from, "toAccountId", to,
                "amount", Map.of("mode", mode, "value", value));
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timed out");
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** A payment to another person that lands in FOOD (by its note). */
    private void dinner(TestUsers.Session s, UUID from, String toNumber, String amount) {
        assertThat(postJson("/api/v1/transfers", Map.of("fromAccountId", from.toString(), "toAccountNumber", toNumber,
                "amount", amount, "note", "dinner"), Bank.authWithKey(s, UUID.randomUUID())).status()).isEqualTo(201);
    }

    private long finishedRuns(String ruleId) {
        return jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ? AND finished_at IS NOT NULL",
                Long.class, UUID.fromString(ruleId));
    }

    // ------------------------------------------------------------------ authoring

    @Test
    void validationPointsIntoTheDslAndTheServerRendersValidRules() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        String savings = Bank.firstAccountId(this, s).toString();
        String g = goal(s, "Goa trip");

        JsonNode bad = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(
                Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 31),
                List.of(transfer(savings, Bank.firstAccountId(this, other).toString(), "PERCENT_OF_TRIGGER", "20"),
                        Map.of("type", "PAY_BILL")),
                "5000.00", "1000.00")), Bank.auth(s)).jsonBody();
        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("fieldErrors").toString()).contains("rule.trigger.dayOfMonth")
                .contains("rule.actions[0].toAccountId").contains("rule.actions[0].amount")
                .contains("rule.actions[1].savedBillerId").contains("rule.caps.perRun");

        JsonNode ok = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(
                Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 1),
                List.of(transfer(savings, g, "FIXED", "5000.00")), "5000.00", "5000.00")), Bank.auth(s)).jsonBody();
        assertThat(ok.get("valid").asBoolean()).isTrue();
        assertThat(ok.get("rendering").asString()).isEqualTo("On the 1st of every month at 08:00 IST: move ₹5,000.00 from "
                + "Savings ••••" + Bank.firstAccountNumber(this, s).substring(8) + " to Goa trip. At most ₹5,000.00 per run "
                + "and ₹5,000.00 a month.");

        Response noStepUp = postJson("/api/v1/autopilot/rules", Map.of("name", "Save", "rule", rule(
                Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 1),
                List.of(transfer(savings, g, "FIXED", "5000.00")), "5000.00", "5000.00")), Bank.auth(s));
        assertThat(noStepUp.status()).isEqualTo(401);
        Response invalid = activate(s, "Save", Map.of("dslVersion", 2));
        assertThat(invalid.status()).isEqualTo(422);
        assertThat(invalid.jsonBody().get("code").asString()).isEqualTo("RULE_INVALID");
    }

    @Test
    void theKillSwitchStopsActivationAndRunsButNotPausing() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String savings = Bank.firstAccountId(this, s).toString();
        String g = goal(s, "Car");
        String id = activate(s, "Weekly", rule(Map.of("type", "SCHEDULE", "frequency", "WEEKLY", "weekday", "MON"),
                List.of(transfer(savings, g, "FIXED", "10.00")), "10.00", "100.00")).jsonBody().get("id").asString();
        autopilotOff();
        assertThat(activate(s, "Another", rule(Map.of("type", "SCHEDULE", "frequency", "WEEKLY", "weekday", "MON"),
                List.of(transfer(savings, g, "FIXED", "10.00")), "10.00", "100.00")).status()).isEqualTo(503);
        assertThat(engine.runDaily(LocalDate.of(2026, 10, 19))).isZero();
        assertThat(postJson("/api/v1/autopilot/rules/" + id + "/pause", "", Bank.auth(s)).jsonBody().get("status")
                .asString()).isEqualTo("PAUSED");
    }

    // ------------------------------------------------------------------ the engine

    @Test
    void aSalarySplitterFiresExactlyOncePerSalaryAndNeverLoops() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session employer = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        String g = goal(s, "Goa trip");
        Map<String, Object> salary = rule(Map.of("type", "MONEY_RECEIVED", "accountId", savings.toString(),
                        "minAmount", "40000.00", "counterpartyContains", "test user"),
                List.of(transfer(savings.toString(), g, "PERCENT_OF_TRIGGER", "20"),
                        Map.of("type", "NOTIFY", "message", "Salary split done")), "30000.00", "60000.00");
        Response created = activate(s, "Salary splitter", salary);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.jsonBody().get("status").asString()).isEqualTo("ACTIVE");
        String ruleId = created.jsonBody().get("id").asString();
        // A rule on the goal itself: Autopilot's own transfer into the goal must never fire it.
        String loopId = activate(s, "Goal watcher", rule(Map.of("type", "MONEY_RECEIVED", "accountId", g),
                List.of(Map.of("type", "NOTIFY", "message", "loop")), "1.00", "1.00")).jsonBody().get("id").asString();

        UUID employerAcc = Bank.firstAccountId(this, employer);
        Bank.deposit(this, employer, employerAcc, "100000.00");
        assertThat(Bank.transferStepUp(this, employer, employerAcc, Bank.firstAccountNumber(this, s), "50000.00",
                UUID.randomUUID()).status()).isEqualTo(201);
        // A small credit is below the trigger's minimum.
        Bank.transfer(this, employer, employerAcc, Bank.firstAccountNumber(this, s), "100.00", UUID.randomUUID());

        await(() -> finishedRuns(ruleId) == 1);
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("10000.00");
        assertThat(Bank.balance(this, s, savings)).isEqualTo("40100.00");
        JsonNode run = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(run.get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("actions").get(0).get("amount").asString()).isEqualTo("10000.00");
        assertThat(run.get("actions").get(0).get("transferId").isNull()).isFalse();
        assertThat(run.get("triggerSummary").asString()).contains("₹50,000.00");
        assertThat(get("/api/v1/transfers", s.accessToken()).body()).contains("\"AUTOPILOT\"");

        // The same event delivered again (at-least-once delivery, a replayed consumer): no second run.
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT e.id, e.type, e.aggregate_id, e.payload::text AS payload, e.created_at FROM outbox_events e
                 WHERE e.type = 'MONEY_MOVED' AND e.payload->'lines' @> ?::jsonb ORDER BY e.created_at LIMIT 1
                """, "[{\"ledgerAccountId\":\"" + savings + "\",\"direction\":\"CREDIT\",\"amount\":\"50000.00\"}]");
        engine.handle(new OutboxEvent((UUID) row.get("id"), (String) row.get("type"), (UUID) row.get("aggregate_id"),
                json.readTree((String) row.get("payload")), ((java.sql.Timestamp) row.get("created_at")).toInstant()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ?", Long.class,
                UUID.fromString(ruleId))).isEqualTo(1);
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("10000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ?", Long.class,
                UUID.fromString(loopId))).isZero();
        JsonNode dto = get("/api/v1/autopilot/rules/" + ruleId, s.accessToken()).jsonBody();
        assertThat(dto.get("runCount").asLong()).isEqualTo(1);
        assertThat(dto.get("movedThisMonth").asString()).isEqualTo("10000.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void aRunInterruptedByACrashResumesWithoutRepeatingFinishedActions() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "10000.00");
        String a = goal(s, "A");
        String b = goal(s, "B");
        String ruleId = activate(s, "Two steps", rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 5),
                List.of(transfer(savings.toString(), a, "FIXED", "1000.00"), transfer(savings.toString(), b, "FIXED", "2000.00")),
                "3000.00", "9000.00")).jsonBody().get("id").asString();

        // The process died after action 0 committed: its result row exists, the run isn't finished.
        UUID runId = jdbc.queryForObject("INSERT INTO autopilot_runs (rule_id, user_id, rule_version, trigger_event_id, "
                + "trigger_summary, planned_total) VALUES (?, ?, 1, 'schedule:2026-11-05', 'Scheduled', 3000) RETURNING id",
                UUID.class, UUID.fromString(ruleId), s.userId());
        jdbc.update("INSERT INTO autopilot_run_actions (run_id, idx, type, amount, status) VALUES (?, 0, 'TRANSFER', 1000, 'DONE')",
                runId);

        engine.executeRun(runId);
        engine.executeRun(runId); // a second resumer is harmless
        assertThat(Bank.balance(this, s, UUID.fromString(a))).isEqualTo("0.00"); // action 0 is not repeated
        assertThat(Bank.balance(this, s, UUID.fromString(b))).isEqualTo("2000.00");
        assertThat(jdbc.queryForObject("SELECT outcome FROM autopilot_runs WHERE id = ?", String.class, runId))
                .isEqualTo("SUCCEEDED");

        // The scheduler claims once per date, however often it runs.
        LocalDate fifth = LocalDate.of(2026, 12, 5);
        engine.runDaily(fifth);
        engine.runDaily(fifth);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ? AND trigger_event_id = ?",
                Long.class, UUID.fromString(ruleId), "schedule:" + fifth)).isEqualTo(1);
        assertThat(Bank.balance(this, s, UUID.fromString(a))).isEqualTo("1000.00");
    }

    @Test
    void anUnreadableRuleIsSkippedAndTheOthersStillRun() {
        TestUsers.Session broken = TestUsers.registerAndLogin(this);
        UUID bad = jdbc.queryForObject("""
                INSERT INTO autopilot_rules (user_id, name, version, status, trigger_type) VALUES (?, 'Corrupt', 1, 'ACTIVE',
                'SCHEDULE') RETURNING id""", UUID.class, broken.userId());
        jdbc.update("INSERT INTO autopilot_rule_versions (rule_id, version, dsl) VALUES (?, 1, '{}')", bad);

        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "500.00");
        String a = goal(s, "A");
        activate(s, "Weekly", rule(Map.of("type", "SCHEDULE", "frequency", "WEEKLY", "weekday", "WED"),
                List.of(transfer(savings.toString(), a, "FIXED", "100.00")), "100.00", "100000.00"));
        LocalDate wed = LocalDate.of(2027, 3, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));

        engine.runDaily(wed); // one corrupt row must not stop everyone else's automation
        assertThat(Bank.balance(this, s, UUID.fromString(a))).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM autopilot_runs WHERE rule_id = ?", Long.class, bad))
                .isZero();
        jdbc.update("UPDATE autopilot_rules SET status = 'DELETED' WHERE id = ?", bad); // leave nothing for other tests
    }

    @Test
    void failuresStopTheRunAndThreeInARowPauseTheRule() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "1500.00");
        String a = goal(s, "A");
        String ruleId = activate(s, "Too much", rule(Map.of("type", "SCHEDULE", "frequency", "WEEKLY", "weekday", "WED"),
                List.of(transfer(savings.toString(), a, "FIXED", "1000.00"), transfer(savings.toString(), a, "FIXED", "1000.00"),
                        Map.of("type", "NOTIFY", "message", "never")), "2000.00", "100000.00")).jsonBody().get("id").asString();
        LocalDate wed = LocalDate.of(2027, 1, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.WEDNESDAY));

        engine.runDaily(wed);
        JsonNode first = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(first.get("outcome").asString()).isEqualTo("PARTIAL");
        assertThat(first.get("actions").size()).isEqualTo(2); // the NOTIFY after the failure never ran
        assertThat(first.get("actions").get(1).get("error").asString()).isNotBlank();
        assertThat(Bank.balance(this, s, savings)).isEqualTo("500.00");

        engine.runDaily(wed.plusWeeks(1));
        engine.runDaily(wed.plusWeeks(2));
        assertThat(get("/api/v1/autopilot/rules/" + ruleId, s.accessToken()).jsonBody().get("status").asString())
                .isEqualTo("ACTIVE"); // FAILED, FAILED: two in a row so far
        engine.runDaily(wed.plusWeeks(3));
        JsonNode paused = get("/api/v1/autopilot/rules/" + ruleId, s.accessToken()).jsonBody();
        assertThat(paused.get("status").asString()).isEqualTo("PAUSED");
        assertThat(paused.get("pausedReason").asString()).contains("3 failed runs");
        assertThat(get("/api/v1/notifications", s.accessToken()).body()).contains("RULE_PAUSED");
        assertThat(Bank.balance(this, s, savings)).isEqualTo("500.00");
    }

    @Test
    void capsSkipARunAndBalanceAboveSweepsTheExcess() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "8000.00");
        String g = goal(s, "Emergency");
        String sweep = activate(s, "Keep lean", rule(Map.of("type", "BALANCE_ABOVE", "accountId", savings.toString(),
                        "threshold", "5000.00"), List.of(transfer(savings.toString(), g, "EXCESS_ABOVE", "5000.00")),
                "50000.00", "100000.00")).jsonBody().get("id").asString();
        String capped = activate(s, "Capped", rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 7),
                List.of(transfer(savings.toString(), g, "FIXED", "1000.00")), "500.00", "500.00")).jsonBody().get("id").asString();

        engine.runDaily(LocalDate.of(2027, 3, 7));
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("3000.00");
        assertThat(Bank.balance(this, s, savings)).isEqualTo("5000.00");
        assertThat(get("/api/v1/autopilot/rules/" + capped + "/runs", s.accessToken()).jsonBody().get("content").get(0)
                .get("outcome").asString()).isEqualTo("SKIPPED_CAP");
        assertThat(finishedRuns(sweep)).isEqualTo(1);
    }

    @Test
    void aPriceCrossingBuysWholeShares() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "50000.00");
        Map<String, Object> inst = jdbc.queryForMap("SELECT symbol, price FROM instruments ORDER BY price LIMIT 1");
        String symbol = (String) inst.get("symbol");
        BigDecimal price = (BigDecimal) inst.get("price");
        String ruleId = activate(s, "Buy the dip", rule(Map.of("type", "PRICE_CROSSES", "symbol", symbol, "direction", "BELOW",
                        "price", price.add(BigDecimal.ONE).setScale(2, java.math.RoundingMode.DOWN).toPlainString()),
                List.of(Map.of("type", "BUY", "accountId", savings.toString(), "symbol", symbol,
                        "amount", Map.of("mode", "FIXED", "value", "5000.00"))), "5000.00", "20000.00")).jsonBody()
                .get("id").asString();

        events.publishEvent(new PricesUpdatedEvent(Map.of(symbol, price)));
        events.publishEvent(new PricesUpdatedEvent(Map.of(symbol, price))); // same day: once
        await(() -> finishedRuns(ruleId) == 1);
        long expected = new BigDecimal("5000.00").divide(price, 0, java.math.RoundingMode.DOWN).longValue();
        assertThat(get("/api/v1/portfolio", s.accessToken()).body()).contains(symbol);
        JsonNode run = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(run.get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("actions").get(0).get("orderId").isNull()).isFalse();
        assertThat(jdbc.queryForObject("SELECT quantity FROM holdings WHERE user_id = ? AND symbol = ?", Long.class,
                s.userId(), symbol)).isEqualTo(expected);
    }

    @Test
    void aBudgetThresholdFiresOnceAMonthWhenSpendingCrossesTheLine() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "5000.00");
        String g = goal(s, "Treat fund");
        Map<String, Object> trigger = Map.of("type", "BUDGET_THRESHOLD", "category", "FOOD", "percent", 90);
        List<Map<String, Object>> actions = List.of(Map.of("type", "NOTIFY", "message", "Food budget is running hot"),
                transfer(savings.toString(), g, "FIXED", "100.00"));

        // A budget has to exist first.
        JsonNode noBudget = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(trigger, actions, "100.00",
                "100.00")), Bank.auth(s)).jsonBody();
        assertThat(noBudget.get("fieldErrors").toString()).contains("rule.trigger.category");
        put("/api/v1/budgets", List.of(Map.of("category", "FOOD", "monthlyLimit", "1000.00")), s.accessToken());
        JsonNode ok = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(trigger, actions, "100.00", "100.00")),
                Bank.auth(s)).jsonBody();
        assertThat(ok.get("rendering").asString()).startsWith("When your food and dining spending reaches 90% of its monthly "
                + "budget (once a month): notify me: “Food budget is running hot”, then move ₹100.00 from Savings");
        String ruleId = activate(s, "Budget guard", rule(trigger, actions, "100.00", "100.00")).jsonBody().get("id")
                .asString();

        String friendNumber = Bank.firstAccountNumber(this, friend);
        dinner(s, savings, friendNumber, "500.00"); // 50%: not yet
        dinner(s, savings, friendNumber, "450.00"); // 95%: fires, via the outbox
        await(() -> finishedRuns(ruleId) == 1);
        dinner(s, savings, friendNumber, "100.00"); // still over: not again this month
        engine.runDaily(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        JsonNode runs = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content");
        assertThat(runs.size()).isEqualTo(1);
        assertThat(runs.get(0).get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(runs.get(0).get("triggerSummary").asString()).startsWith("Food and dining budget at 95% (₹950.00 of ₹1,000.00)");
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("100.00");
        assertThat(get("/api/v1/notifications", s.accessToken()).body()).contains("Food budget is running hot");

        // The replay finds the same crossing.
        JsonNode sim = postJson("/api/v1/autopilot/simulate", Map.of("rule", rule(trigger, actions, "100.00", "100.00"),
                "days", 1), Bank.auth(s)).jsonBody();
        assertThat(sim.get("firings").size()).isEqualTo(1);
        assertThat(sim.get("firings").get(0).get("triggerSummary").asString()).startsWith("Food and dining budget at ");
    }

    @Test
    void aRoundUpSweepMovesTheSpareChangeOfItsWindowOnce() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session friend = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "5000.00");
        String g = goal(s, "Spare change");
        String friendNumber = Bank.firstAccountNumber(this, friend);
        Bank.transfer(this, s, savings, friendNumber, "123.40", UUID.randomUUID()); // +6.60
        Bank.transfer(this, s, savings, friendNumber, "207.00", UUID.randomUUID()); // +3.00
        Bank.transfer(this, s, savings, friendNumber, "50.00", UUID.randomUUID());  // exact: +0

        LocalDate due = LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1);
        String weekday = due.getDayOfWeek().name().substring(0, 3);
        Map<String, Object> sweep = Map.of("type", "ROUND_UP_SWEEP", "fromAccountId", savings.toString(),
                "toAccountId", g, "roundTo", 10);

        JsonNode bad = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(Map.of("type", "MONEY_RECEIVED",
                "accountId", savings.toString()), List.of(Map.of("type", "ROUND_UP_SWEEP", "fromAccountId",
                savings.toString(), "toAccountId", g, "roundTo", 7)), "100.00", "100.00")), Bank.auth(s)).jsonBody();
        assertThat(bad.get("fieldErrors").toString()).contains("rule.actions[0].type").contains("rule.actions[0].roundTo");

        Map<String, Object> rule = rule(Map.of("type", "SCHEDULE", "frequency", "WEEKLY", "weekday", weekday),
                List.of(sweep), "500.00", "2000.00");
        JsonNode created = activate(s, "Round-up saver", rule).jsonBody();
        assertThat(created.get("rendering").asString()).contains("move the round-ups to the next ₹10 on spends from "
                + "Savings ••••" + Bank.firstAccountNumber(this, s).substring(8)
                + " since the previous scheduled day to Spare change");
        String ruleId = created.get("id").asString();

        engine.runDaily(due);
        engine.runDaily(due); // once per scheduled day
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("9.60");
        JsonNode run = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content");
        assertThat(run.size()).isEqualTo(1);
        assertThat(run.get(0).get("actions").get(0).get("amount").asString()).isEqualTo("9.60");
        // The sweep counts toward the caps like any transfer.
        assertThat(get("/api/v1/autopilot/rules/" + ruleId, s.accessToken()).jsonBody().toString()).contains("9.60");
        // The week after has no spends in its window: under ₹1 is skipped, nothing moves.
        engine.runDaily(due.plusWeeks(1));
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("9.60");
    }

    @Test
    void payBillPaysThisCyclesBillOnceAndSkipsWhenPaid() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "10000.00");
        String power = "b1000000-0000-4000-8000-000000000001";
        String saved = postJson("/api/v1/saved-billers", Map.of("billerId", power, "consumerNumber", "9876543210",
                "nickname", "Home power"), Bank.auth(s)).jsonBody().get("id").asString();
        String theirs = postJson("/api/v1/saved-billers", Map.of("billerId", power, "consumerNumber", "1111111111"),
                Bank.auth(other)).jsonBody().get("id").asString();
        Map<String, Object> trigger = Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 3);

        JsonNode bad = postJson("/api/v1/autopilot/validate", Map.of("rule", rule(trigger, List.of(Map.of("type",
                "PAY_BILL", "savedBillerId", theirs, "fromAccountId", savings.toString())), "5000.00", "5000.00")),
                Bank.auth(s)).jsonBody();
        assertThat(bad.get("fieldErrors").toString()).contains("rule.actions[0].savedBillerId");

        JsonNode created = activate(s, "Power bill", rule(trigger, List.of(Map.of("type", "PAY_BILL", "savedBillerId",
                saved, "fromAccountId", savings.toString())), "5000.00", "5000.00")).jsonBody();
        assertThat(created.get("rendering").asString()).contains("pay the Metro Power Distribution bill (Home power) "
                + "from Savings ••••" + Bank.firstAccountNumber(this, s).substring(8) + " (if it isn't paid yet)");
        String ruleId = created.get("id").asString();

        engine.runDaily(LocalDate.of(2027, 5, 3));
        JsonNode run = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(run.get("outcome").asString()).isEqualTo("SUCCEEDED");
        BigDecimal paid = new BigDecimal(run.get("actions").get(0).get("amount").asString());
        assertThat(new BigDecimal(Bank.balance(this, s, savings))).isEqualByComparingTo(new BigDecimal("10000.00")
                .subtract(paid));
        assertThat(get("/api/v1/bill-payments", s.accessToken()).jsonBody().get("totalElements").asLong()).isEqualTo(1);

        // Another due day in the same bill cycle: already paid, so the action is skipped and nothing moves.
        engine.runDaily(LocalDate.of(2027, 6, 3));
        JsonNode again = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(again.get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(again.get("actions").get(0).get("amount").isNull()).isTrue(); // skipped
        assertThat(again.get("actions").get(0).get("error").isNull()).isTrue();
        assertThat(get("/api/v1/bill-payments", s.accessToken()).jsonBody().get("totalElements").asLong()).isEqualTo(1);

        // A removed biller fails the next run with a clear reason instead of silently skipping.
        assertThat(delete("/api/v1/saved-billers/" + saved, s.accessToken()).status()).isEqualTo(204);
        engine.runDaily(LocalDate.of(2027, 7, 3));
        JsonNode failed = get("/api/v1/autopilot/rules/" + ruleId + "/runs", s.accessToken()).jsonBody().get("content").get(0);
        assertThat(failed.get("outcome").asString()).isEqualTo("FAILED");
        assertThat(failed.get("actions").get(0).get("error").asString()).isEqualTo("The saved biller was removed");
    }

    // ------------------------------------------------------------------ replay, lifecycle, intents

    @Test
    void theReplayShowsEveryPastFiringWithoutMovingMoney() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID savings = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, savings, "45000.00");
        Bank.deposit(this, s, savings, "100.00");
        Bank.deposit(this, s, savings, "42000.00");
        String g = goal(s, "Goa trip");
        JsonNode sim = postJson("/api/v1/autopilot/simulate", Map.of("rule", rule(Map.of("type", "MONEY_RECEIVED",
                        "accountId", savings.toString(), "minAmount", "40000.00"),
                List.of(transfer(savings.toString(), g, "PERCENT_OF_TRIGGER", "10")), "8000.00", "8000.00"), "days", 30),
                Bank.auth(s)).jsonBody();
        assertThat(sim.get("firings").size()).isEqualTo(2);
        assertThat(sim.get("firings").get(0).get("actions").get(0).get("amount").asString()).isEqualTo("4200.00");
        assertThat(sim.get("firings").get(0).get("actions").get(0).get("wouldSucceed").asBoolean()).isFalse(); // monthly cap
        assertThat(sim.get("totalMoved").asString()).isEqualTo("4500.00");
        assertThat(sim.get("failures").asInt()).isEqualTo(1);
        assertThat(Bank.balance(this, s, UUID.fromString(g))).isEqualTo("0.00");
        assertThat(postJson("/api/v1/autopilot/simulate", Map.of("rule", Map.of("dslVersion", 1)), Bank.auth(s)).status())
                .isEqualTo(422);
    }

    @Test
    void editsAreNewVersionsAndRulesArePrivate() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        TestUsers.Session other = TestUsers.registerAndLogin(this);
        String savings = Bank.firstAccountId(this, s).toString();
        String g = goal(s, "Bike");
        Map<String, Object> r = rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 2),
                List.of(transfer(savings, g, "FIXED", "100.00")), "100.00", "100.00");
        String id = activate(s, "Bike fund", r).jsonBody().get("id").asString();
        Map<String, String> h = new HashMap<>(Bank.auth(s));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "RULE_ACTIVATE"));
        JsonNode v2 = put("/api/v1/autopilot/rules/" + id, Map.of("name", "Bike fund+", "rule",
                rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 3),
                        List.of(transfer(savings, g, "FIXED", "200.00")), "200.00", "200.00")), s.accessToken()).jsonBody();
        assertThat(v2.get("code").asString()).isEqualTo("STEP_UP_REQUIRED"); // put() helper sends no step-up
        JsonNode updated = send(request("/api/v1/autopilot/rules/" + id).header("Authorization", h.get("Authorization"))
                .header("X-Step-Up-Token", h.get("X-Step-Up-Token")).header("Content-Type", "application/json")
                .PUT(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("name", "Bike fund+",
                        "rule", rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY", "dayOfMonth", 3),
                                List.of(transfer(savings, g, "FIXED", "200.00")), "200.00", "200.00"))))).build()).jsonBody();
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("rendering").asString()).startsWith("On the 3rd of every month");

        assertThat(get("/api/v1/autopilot/rules/" + id, other.accessToken()).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/autopilot/rules/" + id + "/pause", "", Bank.auth(other)).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/autopilot/rules/" + id + "/pause", "", Bank.auth(s)).status()).isEqualTo(200);
        assertThat(postJson("/api/v1/autopilot/rules/" + id + "/resume", "", Bank.auth(s)).status()).isEqualTo(401);
        assertThat(delete("/api/v1/autopilot/rules/" + id, s.accessToken()).status()).isEqualTo(204);
        assertThat(get("/api/v1/autopilot/rules", s.accessToken()).jsonBody().size()).isZero();
        assertThat(get("/api/v1/autopilot/templates", s.accessToken()).jsonBody().size()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void anAutopilotRuleIntentActivatesOnlyWhenApproved() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        String savings = Bank.firstAccountId(this, s).toString();
        String g = goal(s, "House");
        JsonNode intent = postJson("/api/v1/intents", Map.of("kind", "AUTOPILOT_RULE", "rationale", "You asked for it",
                "payload", Map.of("name", "House fund", "rule", rule(Map.of("type", "SCHEDULE", "frequency", "MONTHLY",
                        "dayOfMonth", 10), List.of(transfer(savings, g, "FIXED", "2500.00")), "2500.00", "2500.00"))),
                Bank.auth(s)).jsonBody();
        assertThat(intent.get("summary").get("title").asString()).isEqualTo("Turn on Autopilot: House fund");
        assertThat(intent.get("summary").get("lines").get(0).get("value").asString()).startsWith("On the 10th of every month");
        assertThat(intent.get("requiresStepUp").asBoolean()).isTrue();
        assertThat(get("/api/v1/autopilot/rules", s.accessToken()).jsonBody().size()).isZero();

        Map<String, String> h = new HashMap<>(Bank.authWithKey(s, UUID.randomUUID()));
        h.put("X-Step-Up-Token", Bank.stepUpToken(this, s, "INTENT_APPROVE"));
        JsonNode done = postJson("/api/v1/intents/" + intent.get("id").asString() + "/approve", "", h).jsonBody();
        assertThat(done.get("status").asString()).isEqualTo("EXECUTED");
        assertThat(done.get("resultRef").get("type").asString()).isEqualTo("AUTOPILOT_RULE");
        assertThat(get("/api/v1/autopilot/rules/" + done.get("resultRef").get("id").asString(), s.accessToken())
                .jsonBody().get("status").asString()).isEqualTo("ACTIVE");
    }
}
