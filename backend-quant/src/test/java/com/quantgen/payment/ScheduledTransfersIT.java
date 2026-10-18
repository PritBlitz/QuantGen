package com.quantgen.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.payment.service.ScheduledTransferService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M2 part 2: scheduled and recurring transfers (F-PAY-9). The job is driven with explicit run dates. */
class ScheduledTransfersIT extends IntegrationTest {

    @Autowired
    ScheduledTransferService scheduled;

    @Autowired
    LedgerService ledger;

    private static LocalDate today() {
        return LocalDate.now(TimeConfig.BUSINESS_ZONE);
    }

    /** A future start date on day 1-28, so it is valid for monthly instructions too. */
    private static LocalDate start() {
        LocalDate d = today().plusDays(1);
        return d.getDayOfMonth() > 28 ? d.withDayOfMonth(1).plusMonths(1) : d;
    }

    @Test
    void creationIsValidated() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        String bNum = Bank.firstAccountNumber(this, bob);

        assertThat(create(alice, body(aAcc, bNum, "10.00", today(), "ONCE")).status()).isEqualTo(400);
        LocalDate the29th = today().plusMonths(1).withDayOfMonth(29);
        assertThat(create(alice, body(aAcc, bNum, "10.00", the29th, "MONTHLY")).status()).isEqualTo(400);
        Map<String, Object> onceWithCount = body(aAcc, bNum, "10.00", start(), "ONCE");
        onceWithCount.put("count", 3);
        assertThat(create(alice, onceWithCount).status()).isEqualTo(400);
        Map<String, Object> endBeforeStart = body(aAcc, bNum, "10.00", start(), "WEEKLY");
        endBeforeStart.put("endDate", start().toString());
        assertThat(create(alice, endBeforeStart).status()).isEqualTo(400);
        assertThat(create(alice, body(aAcc, Bank.firstAccountNumber(this, alice), "10.00", start(), "ONCE")).status())
                .isEqualTo(400); // to itself
        assertThat(postJson("/api/v1/scheduled-transfers", body(aAcc, bNum, "10.00", start(), "ONCE"), Bank.auth(alice))
                .status()).isEqualTo(400); // no Idempotency-Key

        // Someone else's source account → 404.
        assertThat(create(bob, body(aAcc, bNum, "10.00", start(), "ONCE")).status()).isEqualTo(404);

        Response ok = create(alice, body(aAcc, bNum, "10.00", start(), "ONCE"));
        assertThat(ok.status()).isEqualTo(201);
        JsonNode dto = ok.jsonBody();
        assertThat(dto.get("toName").asString()).isEqualTo("Test U.");
        assertThat(dto.get("toAccountNumberMasked").asString()).isEqualTo("••••" + bNum.substring(8));
        assertThat(dto.get("remainingCount").asInt()).isEqualTo(1);
        assertThat(dto.get("nextRunDate").asString()).isEqualTo(start().toString());
        assertThat(dto.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(dto.get("lastRun").isNull()).isTrue();
        assertThat(dto.toString()).doesNotContain(bNum);
    }

    @Test
    void oneOffRunsOnceAndCompletes() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        Bank.deposit(this, alice, aAcc, "500.00");
        String id = create(alice, body(aAcc, Bank.firstAccountNumber(this, bob), "120.00", start(), "ONCE"))
                .jsonBody().get("id").asString();

        scheduled.runDue(start().minusDays(1)); // not due yet
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("0.00");

        // Two overlapping job runs on the day: the occurrence is paid exactly once.
        CompletableFuture<Integer> a = CompletableFuture.supplyAsync(() -> scheduled.runDue(start()));
        CompletableFuture<Integer> b = CompletableFuture.supplyAsync(() -> scheduled.runDue(start()));
        a.get();
        b.get();
        scheduled.runDue(start().plusDays(1));

        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("120.00");
        assertThat(Bank.balance(this, alice, aAcc)).isEqualTo("380.00");
        JsonNode dto = find(alice, id);
        assertThat(dto.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(dto.get("nextRunDate").isNull()).isTrue();
        assertThat(dto.get("lastRun").get("outcome").asString()).isEqualTo("SUCCEEDED");
        String transferId = dto.get("lastRun").get("transferId").asString();
        JsonNode transfer = get("/api/v1/transfers", alice.accessToken()).jsonBody().get("content").get(0);
        assertThat(transfer.get("id").asString()).isEqualTo(transferId);
        assertThat(transfer.get("source").asString()).isEqualTo("SCHEDULED");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void weeklyWithCountAdvancesAndCompletes() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        Bank.deposit(this, alice, aAcc, "500.00");
        Map<String, Object> req = body(aAcc, Bank.firstAccountNumber(this, bob), "50.00", start(), "WEEKLY");
        req.put("count", 2);
        String id = create(alice, req).jsonBody().get("id").asString();

        scheduled.runDue(start());
        JsonNode afterFirst = find(alice, id);
        assertThat(afterFirst.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(afterFirst.get("nextRunDate").asString()).isEqualTo(start().plusWeeks(1).toString());
        assertThat(afterFirst.get("remainingCount").asInt()).isEqualTo(1);

        scheduled.runDue(start().plusDays(3)); // nothing due mid-week
        scheduled.runDue(start().plusWeeks(1));
        JsonNode done = find(alice, id);
        assertThat(done.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(done.get("remainingCount").asInt()).isZero();
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("100.00");
    }

    @Test
    void insufficientFundsRetriesOnceThenMisses() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String id = create(alice, body(aAcc, Bank.firstAccountNumber(this, bob), "100.00", start(), "MONTHLY"))
                .jsonBody().get("id").asString();

        scheduled.runDue(start());
        JsonNode retrying = find(alice, id);
        assertThat(retrying.get("lastRun").get("outcome").asString()).isEqualTo("RETRYING");
        assertThat(retrying.get("nextRunDate").asString()).isEqualTo(start().plusDays(1).toString());
        assertThat(get("/api/v1/notifications", alice.accessToken()).body()).contains("SCHEDULED_FAILED");

        scheduled.runDue(start().plusDays(1));
        JsonNode missed = find(alice, id);
        assertThat(missed.get("lastRun").get("outcome").asString()).isEqualTo("MISSED");
        assertThat(missed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(missed.get("nextRunDate").asString()).isEqualTo(start().plusMonths(1).toString());

        // Next month the money is there: paid on the first try.
        Bank.deposit(this, alice, aAcc, "100.00");
        scheduled.runDue(start().plusMonths(1));
        assertThat(find(alice, id).get("lastRun").get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("100.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scheduled_transfer_runs WHERE scheduled_transfer_id = ?::uuid",
                Integer.class, id)).isEqualTo(3);
    }

    @Test
    void retryNextDaySucceedsWhenFundsArrive() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        String id = create(alice, body(aAcc, Bank.firstAccountNumber(this, bob), "75.00", start(), "ONCE"))
                .jsonBody().get("id").asString();
        scheduled.runDue(start());
        Bank.deposit(this, alice, aAcc, "100.00");
        scheduled.runDue(start().plusDays(1));
        JsonNode dto = find(alice, id);
        assertThat(dto.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(dto.get("lastRun").get("outcome").asString()).isEqualTo("SUCCEEDED");
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("75.00");
    }

    @Test
    void pauseResumeAndCancel() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        UUID bAcc = Bank.firstAccountId(this, bob);
        Bank.deposit(this, alice, aAcc, "500.00");
        String id = create(alice, body(aAcc, Bank.firstAccountNumber(this, bob), "10.00", start(), "WEEKLY"))
                .jsonBody().get("id").asString();

        assertThat(postJson("/api/v1/scheduled-transfers/" + id + "/resume", Map.of(), Bank.auth(alice)).status())
                .isEqualTo(409);
        assertThat(postJson("/api/v1/scheduled-transfers/" + id + "/pause", Map.of(), Bank.auth(bob)).status())
                .isEqualTo(404);
        assertThat(postJson("/api/v1/scheduled-transfers/" + id + "/pause", Map.of(), Bank.auth(alice))
                .jsonBody().get("status").asString()).isEqualTo("PAUSED");
        scheduled.runDue(start());
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("0.00");

        // Paused for three weeks: resuming skips the missed weeks and keeps the weekday.
        LocalDate threeWeeksAgo = today().minusWeeks(3);
        jdbc.update("UPDATE scheduled_transfers SET next_run_date = ? WHERE id = ?::uuid",
                java.sql.Date.valueOf(threeWeeksAgo), id);
        JsonNode resumed = postJson("/api/v1/scheduled-transfers/" + id + "/resume", Map.of(), Bank.auth(alice))
                .jsonBody();
        LocalDate next = LocalDate.parse(resumed.get("nextRunDate").asString());
        assertThat(next).isAfter(today()).isBeforeOrEqualTo(today().plusWeeks(1));
        assertThat(next.getDayOfWeek()).isEqualTo(threeWeeksAgo.getDayOfWeek());
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("0.00");

        assertThat(delete("/api/v1/scheduled-transfers/" + id, bob.accessToken()).status()).isEqualTo(404);
        assertThat(delete("/api/v1/scheduled-transfers/" + id, alice.accessToken()).status()).isEqualTo(204);
        assertThat(delete("/api/v1/scheduled-transfers/" + id, alice.accessToken()).status()).isEqualTo(204);
        JsonNode cancelled = find(alice, id);
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.get("nextRunDate").isNull()).isTrue();
        scheduled.runDue(next);
        assertThat(Bank.balance(this, bob, bAcc)).isEqualTo("0.00");
    }

    @Test
    void largeInstructionNeedsStepUpAtCreation() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID aAcc = Bank.firstAccountId(this, alice);
        Map<String, Object> req = body(aAcc, Bank.firstAccountNumber(this, bob), "60000.00", start(), "ONCE");
        UUID key = UUID.randomUUID();
        Response prompt = postJson("/api/v1/scheduled-transfers", req, Bank.authWithKey(alice, key));
        assertThat(prompt.status()).isEqualTo(401);
        assertThat(prompt.jsonBody().get("code").asString()).isEqualTo("STEP_UP_REQUIRED");

        Map<String, String> headers = new HashMap<>(Bank.authWithKey(alice, key));
        headers.put("X-Step-Up-Token", Bank.stepUpToken(this, alice, "TRANSFER"));
        assertThat(postJson("/api/v1/scheduled-transfers", req, headers).status()).isEqualTo(201);
        // A retry with the same key replays the same instruction instead of creating a second one.
        Response replay = postJson("/api/v1/scheduled-transfers", req, headers);
        assertThat(replay.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(get("/api/v1/scheduled-transfers", alice.accessToken()).jsonBody().size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> body(UUID from, String toNumber, String amount, LocalDate start, String frequency) {
        Map<String, Object> b = new HashMap<>();
        b.put("fromAccountId", from.toString());
        b.put("toAccountNumber", toNumber);
        b.put("amount", amount);
        b.put("startDate", start.toString());
        b.put("frequency", frequency);
        b.put("note", "Rent");
        return b;
    }

    private Response create(TestUsers.Session s, Map<String, Object> body) {
        return postJson("/api/v1/scheduled-transfers", body, Bank.authWithKey(s, UUID.randomUUID()));
    }

    private JsonNode find(TestUsers.Session s, String id) {
        for (JsonNode n : get("/api/v1/scheduled-transfers", s.accessToken()).jsonBody()) {
            if (n.get("id").asString().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("scheduled transfer " + id + " not listed");
    }
}
