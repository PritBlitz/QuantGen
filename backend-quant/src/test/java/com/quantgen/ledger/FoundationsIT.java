package com.quantgen.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.converter.JacksonJsonMessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** V2-M1 foundations: holds and available balance, outbox, realtime notifications, feature flags. */
class FoundationsIT extends IntegrationTest {

    @Autowired
    LedgerService ledger;

    @Autowired
    TransactionTemplate tx;

    // ------------------------------------------------------------------ holds

    @Test
    void holdReducesAvailableBalanceButNotBalance() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "1000.00");

        UUID hold = placeHold(acc, "300.00");

        JsonNode dto = get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody();
        assertThat(dto.get("balance").asString()).isEqualTo("1000.00");
        assertThat(dto.get("holdsTotal").asString()).isEqualTo("300.00");
        assertThat(dto.get("availableBalance").asString()).isEqualTo("700.00");

        tx.executeWithoutResult(st -> ledger.releaseHold(hold));
        assertThat(get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody().get("availableBalance").asString())
                .isEqualTo("1000.00");
    }

    @Test
    void heldMoneyCannotBeTransferredButTheRestCan() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(alice, "1000.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);
        placeHold(acc, "800.00");

        Response tooMuch = Bank.transfer(this, alice, acc, bobNumber, "300.00", UUID.randomUUID());
        assertThat(tooMuch.status()).isEqualTo(422);
        assertThat(tooMuch.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(Bank.transfer(this, alice, acc, bobNumber, "200.00", UUID.randomUUID()).status()).isEqualTo(201);
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("800.00");
    }

    @Test
    void holdCannotExceedAvailableAndIsRejectedForSystemAccounts() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "100.00");
        placeHold(acc, "60.00");

        assertThatThrownBy(() -> placeHold(acc, "50.00"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS));
        assertThatThrownBy(() -> tx.executeWithoutResult(st ->
                ledger.placeHold(SystemAccount.BANK_CASH.id(), new BigDecimal("1.00"), "TEST", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> placeHold(acc, "0.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capturingAHoldSettlesItAndEnforcesTheAmount() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "500.00");
        UUID hold = placeHold(acc, "200.00");

        // A debit larger than the hold may not use it.
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.post(JournalRequest
                .builder(JournalType.TRADE, "too big", s.userId())
                .debit(acc, new BigDecimal("250.00"), null)
                .credit(SystemAccount.BROKERAGE_CLEARING.id(), new BigDecimal("250.00"), null)
                .captureHold(hold).build()))).isInstanceOf(IllegalArgumentException.class);

        tx.executeWithoutResult(st -> ledger.post(JournalRequest.builder(JournalType.TRADE, "settle", s.userId())
                .debit(acc, new BigDecimal("150.00"), null)
                .credit(SystemAccount.BROKERAGE_CLEARING.id(), new BigDecimal("150.00"), null)
                .captureHold(hold).build()));

        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, hold)).isEqualTo("CAPTURED");
        assertThat(Bank.balance(this, s, acc)).isEqualTo("350.00");
        // A captured hold cannot be captured or released again.
        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.releaseHold(hold)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void expiredHoldsAreReleased() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "100.00");
        UUID hold = tx.execute(st -> ledger.placeHold(acc, new BigDecimal("40.00"), "TEST", "expiry",
                Instant.now().minusSeconds(5)));

        Integer expired = tx.execute(st -> ledger.expireHolds(Instant.now()));

        assertThat(expired).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, hold)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentHoldsAndTransfersNeverSpendTheSameRupee() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(alice, "100.00");
        String bobNumber = Bank.firstAccountNumber(this, bob);

        // 20 competing spenders, each wanting 30 of the 100: holds and transfers mixed.
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean hold = i % 2 == 0;
            results.add(pool.submit(() -> hold
                    ? tryHold(acc, "30.00")
                    : Bank.transfer(this, alice, acc, bobNumber, "30.00", UUID.randomUUID()).status() == 201));
        }
        long succeeded = 0;
        for (Future<Boolean> f : results) {
            succeeded += f.get(60, TimeUnit.SECONDS) ? 1 : 0;
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(3); // 3 x 30 = 90 <= 100 < 120
        BigDecimal balance = new BigDecimal(Bank.balance(this, alice, acc));
        BigDecimal holds = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM holds "
                + "WHERE ledger_account_id = ? AND status = 'ACTIVE'", BigDecimal.class, acc);
        assertThat(balance.subtract(holds)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    // ------------------------------------------------------------------ outbox

    @Test
    void everyPostWritesAnOutboxEventThatTheConsumerPollerMarksProcessed() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "250.00");

        List<Map<String, Object>> events = jdbc.queryForList("SELECT id, payload::text AS payload FROM outbox_events "
                + "WHERE type = 'MONEY_MOVED' AND payload::text LIKE ?", "%" + acc + "%");
        assertThat(events).hasSize(1);
        String payload = (String) events.getFirst().get("payload");
        assertThat(payload).contains("\"250.00\"").doesNotContain("Test User", s.email());

        UUID id = (UUID) events.getFirst().get("id");
        long deadline = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("SELECT processed_at IS NULL FROM outbox_events WHERE id = ?", Boolean.class, id)
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT processed_at IS NOT NULL FROM outbox_events WHERE id = ?",
                Boolean.class, id)).isTrue();
    }

    @Test
    void aRolledBackPostLeavesNoOutboxEvent() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = fundedAccount(s, "10.00");
        Integer before = jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class);

        assertThatThrownBy(() -> tx.executeWithoutResult(st -> ledger.post(JournalRequest
                .builder(JournalType.TRADE, "overdraw", s.userId())
                .debit(acc, new BigDecimal("999.00"), null)
                .credit(SystemAccount.BROKERAGE_CLEARING.id(), new BigDecimal("999.00"), null).build())))
                .isInstanceOf(ApiException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ realtime notifications

    @Test
    void notificationsArePushedOnlyToTheirOwner() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        TestUsers.Session carol = TestUsers.registerAndLogin(this);
        UUID aliceAcc = fundedAccount(alice, "100.00");

        BlockingQueue<JsonNode> bobInbox = new LinkedBlockingQueue<>();
        BlockingQueue<JsonNode> carolInbox = new LinkedBlockingQueue<>();
        StompSession bobSession = subscribe(bob, bobInbox);
        StompSession carolSession = subscribe(carol, carolInbox);
        Thread.sleep(300);

        assertThat(Bank.transfer(this, alice, aliceAcc, Bank.firstAccountNumber(this, bob), "25.00", UUID.randomUUID())
                .status()).isEqualTo(201);

        JsonNode pushed = bobInbox.poll(10, TimeUnit.SECONDS);
        assertThat(pushed).isNotNull();
        assertThat(pushed.get("type").asString()).isEqualTo("MONEY_RECEIVED");
        assertThat(pushed.get("read").asBoolean()).isFalse();
        assertThat(carolInbox.poll(1, TimeUnit.SECONDS)).isNull();
        bobSession.disconnect();
        carolSession.disconnect();
    }

    @Test
    void aUserCannotSubscribeToAnotherQueue() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        StompSession session = connect(s);
        session.subscribe("/queue/notifications", new StompSessionHandlerAdapter() { });
        // The broker closes the connection on a forbidden destination.
        long deadline = System.currentTimeMillis() + 5_000;
        while (session.isConnected() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(session.isConnected()).isFalse();
    }

    // ------------------------------------------------------------------ feature flags

    @Test
    void flagsAreVisibleToUsersAndTogglableOnlyByAdmins() {
        TestUsers.Session user = TestUsers.registerAndLogin(this);
        TestUsers.Session admin = Bank.admin(this);

        assertThat(get("/api/v1/feature-flags").status()).isEqualTo(401);
        JsonNode flags = get("/api/v1/feature-flags", user.accessToken()).jsonBody();
        assertThat(flags.size()).isEqualTo(6); // ai, autopilot, cards, crypto, mcp, razorpay
        assertThat(flags.get("ai").asBoolean()).isFalse();

        assertThat(get("/api/v1/admin/feature-flags", user.accessToken()).status()).isEqualTo(403);
        assertThat(put("/api/v1/admin/feature-flags/autopilot", Map.of("enabled", true), user.accessToken()).status())
                .isEqualTo(403);

        Response toggled = put("/api/v1/admin/feature-flags/autopilot", Map.of("enabled", true), admin.accessToken());
        assertThat(toggled.status()).isEqualTo(200);
        assertThat(toggled.jsonBody().get("enabled").asBoolean()).isTrue();
        assertThat(get("/api/v1/feature-flags", user.accessToken()).jsonBody().get("autopilot").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'FEATURE_FLAG_SET' "
                + "AND target_id = 'autopilot'", Integer.class)).isGreaterThanOrEqualTo(1);

        assertThat(put("/api/v1/admin/feature-flags/nope", Map.of("enabled", true), admin.accessToken()).status())
                .isEqualTo(404);
        assertThat(put("/api/v1/admin/feature-flags/autopilot", Map.of(), admin.accessToken()).status()).isEqualTo(400);
        put("/api/v1/admin/feature-flags/autopilot", Map.of("enabled", false), admin.accessToken());
    }

    // ------------------------------------------------------------------ helpers

    private UUID fundedAccount(TestUsers.Session s, String amount) {
        UUID acc = Bank.firstAccountId(this, s);
        assertThat(Bank.deposit(this, s, acc, amount).status()).isEqualTo(201);
        return acc;
    }

    private UUID placeHold(UUID acc, String amount) {
        return tx.execute(st -> ledger.placeHold(acc, new BigDecimal(amount), "TEST", "it", null));
    }

    private boolean tryHold(UUID acc, String amount) {
        try {
            placeHold(acc, amount);
            return true;
        } catch (ApiException e) {
            return false;
        }
    }

    private StompSession connect(TestUsers.Session s) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter(json));
        StompHeaders headers = new StompHeaders();
        headers.add("Authorization", "Bearer " + s.accessToken());
        return client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);
    }

    private StompSession subscribe(TestUsers.Session s, BlockingQueue<JsonNode> inbox) throws Exception {
        StompSession session = connect(s);
        session.subscribe("/user/queue/notifications", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                inbox.add((JsonNode) payload);
            }
        });
        return session;
    }
}
