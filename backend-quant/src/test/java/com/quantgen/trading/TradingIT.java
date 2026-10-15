package com.quantgen.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;
import com.quantgen.trading.service.PriceSimulator;

import tools.jackson.databind.JsonNode;

/** M3: market data, buy/sell atomicity, oversell rejection, portfolio P&L, live prices over STOMP. */
class TradingIT extends IntegrationTest {

    @Autowired
    PriceSimulator simulator;

    @Autowired
    LedgerService ledger;

    // ------------------------------------------------------------------ market data

    @Test
    void instrumentsRequireAuthAndUseStringPrices() {
        assertThat(get("/api/v1/market/instruments").status()).isEqualTo(401);
        TestUsers.Session s = TestUsers.registerAndLogin(this);

        JsonNode list = get("/api/v1/market/instruments", s.accessToken()).jsonBody();
        assertThat(list.size()).isEqualTo(15);
        JsonNode first = list.get(0);
        assertThat(first.get("price").isString()).isTrue();
        assertThat(first.get("changePct24h").isString()).isTrue();
        assertThat(first.get("symbol").asString()).isNotBlank();

        JsonNode tcs = get("/api/v1/market/instruments/tcs", s.accessToken()).jsonBody();
        assertThat(tcs.get("symbol").asString()).isEqualTo("TCS");
        assertThat(get("/api/v1/market/instruments/NOPE", s.accessToken()).status()).isEqualTo(404);
    }

    @Test
    void simulatorTicksMovePricesAndFeedHistory() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        for (int i = 0; i < 3; i++) {
            assertThat(simulator.tick()).hasSize(15);
        }
        JsonNode history = get("/api/v1/market/instruments/INFY/history?range=1H", s.accessToken()).jsonBody();
        assertThat(history.size()).isGreaterThanOrEqualTo(1);
        assertThat(history.get(0).get("t").asString()).endsWith("Z");
        assertThat(history.get(0).get("price").isString()).isTrue();
        assertThat(get("/api/v1/market/instruments/INFY/history?range=1D", s.accessToken()).status()).isEqualTo(200);
        assertThat(get("/api/v1/market/instruments/INFY/history?range=1W", s.accessToken()).status()).isEqualTo(200);
        assertThat(get("/api/v1/market/instruments/INFY/history?range=5Y", s.accessToken()).status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM instruments WHERE price <= 1", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ orders

    @Test
    void buyThenSellSettlesBothLegsAndPortfolioIsExact() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "10000.00");
        setPrice("QGEN", "100.00");

        Response buy = order(s, acc, "QGEN", "BUY", 10);
        assertThat(buy.status()).isEqualTo(201);
        assertThat(buy.jsonBody().get("price").asString()).isEqualTo("100.00");
        assertThat(buy.jsonBody().get("total").asString()).isEqualTo("1000.00");
        assertThat(buy.jsonBody().get("status").asString()).isEqualTo("FILLED");
        assertThat(Bank.balance(this, s, acc)).isEqualTo("9000.00");

        setPrice("QGEN", "110.00");
        assertThat(order(s, acc, "QGEN", "BUY", 10).status()).isEqualTo(201); // avg cost 105
        assertThat(Bank.balance(this, s, acc)).isEqualTo("7900.00");

        setPrice("QGEN", "120.00");
        Response sell = order(s, acc, "QGEN", "SELL", 5);
        assertThat(sell.status()).isEqualTo(201);
        assertThat(sell.jsonBody().get("total").asString()).isEqualTo("600.00");
        assertThat(Bank.balance(this, s, acc)).isEqualTo("8500.00");

        JsonNode p = get("/api/v1/portfolio", s.accessToken()).jsonBody();
        JsonNode h = p.get("holdings").get(0);
        assertThat(h.get("symbol").asString()).isEqualTo("QGEN");
        assertThat(h.get("name").asString()).isEqualTo("QuantGen Holdings");
        assertThat(h.get("quantity").asLong()).isEqualTo(15);
        assertThat(h.get("avgCost").asString()).isEqualTo("105.00");
        assertThat(h.get("price").asString()).isEqualTo("120.00");
        assertThat(h.get("marketValue").asString()).isEqualTo("1800.00");
        assertThat(h.get("unrealizedPnl").asString()).isEqualTo("225.00");     // 1800 − 1575
        assertThat(h.get("unrealizedPnlPct").asString()).isEqualTo("14.29");   // 225 / 1575
        assertThat(p.get("totalMarketValue").asString()).isEqualTo("1800.00");
        assertThat(p.get("totalCost").asString()).isEqualTo("1575.00");
        assertThat(p.get("totalUnrealizedPnl").asString()).isEqualTo("225.00");
        assertThat(p.get("totalRealizedPnl").asString()).isEqualTo("75.00");    // 600 − 525

        JsonNode orders = get("/api/v1/orders", s.accessToken()).jsonBody();
        assertThat(orders.get("totalElements").asLong()).isEqualTo(3);
        assertThat(orders.get("content").get(0).get("side").asString()).isEqualTo("SELL");

        // The trade legs are in the account's history and the ledger still balances.
        JsonNode trades = get("/api/v1/accounts/" + acc + "/transactions?type=TRADE", s.accessToken()).jsonBody();
        assertThat(trades.get("totalElements").asLong()).isEqualTo(3);
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void oversellIsRejectedWithoutSideEffects() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "5000.00");
        setPrice("TCS", "100.00");
        order(s, acc, "TCS", "BUY", 3);

        Response res = order(s, acc, "TCS", "SELL", 4);
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_HOLDINGS");

        Response never = order(s, acc, "INFY", "SELL", 1); // never held
        assertThat(never.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_HOLDINGS");

        assertThat(Bank.balance(this, s, acc)).isEqualTo("4700.00");
        assertThat(get("/api/v1/orders", s.accessToken()).jsonBody().get("totalElements").asLong()).isEqualTo(1);
        assertThat(get("/api/v1/portfolio", s.accessToken()).jsonBody().get("holdings").get(0).get("quantity").asLong())
                .isEqualTo(3);
    }

    @Test
    void buyWithoutFundsRollsBackTheHoldingLeg() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "99.99");
        setPrice("HDFC", "100.00");

        Response res = order(s, acc, "HDFC", "BUY", 1);
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holdings WHERE user_id = ? AND quantity > 0",
                Integer.class, s.userId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, s.userId())).isZero();
        assertThat(Bank.balance(this, s, acc)).isEqualTo("99.99");
    }

    @Test
    void orderValidation() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "1000.00");
        assertThat(order(s, acc, "QGEN", "BUY", 0).status()).isEqualTo(400);
        assertThat(order(s, acc, "QGEN", "BUY", 10_001).status()).isEqualTo(400);
        assertThat(order(s, acc, "NOPE", "BUY", 1).status()).isEqualTo(400);
        assertThat(postJson("/api/v1/orders", "{\"accountId\":\"" + acc + "\",\"symbol\":\"QGEN\",\"side\":\"BUY\",\"quantity\":1.5}",
                Bank.authWithKey(s, UUID.randomUUID())).status()).isEqualTo(400);
        assertThat(postJson("/api/v1/orders", Map.of("accountId", acc.toString(), "symbol", "QGEN", "side", "HOLD",
                "quantity", 1), Bank.authWithKey(s, UUID.randomUUID())).status()).isEqualTo(400);

        TestUsers.Session other = TestUsers.registerAndLogin(this);
        assertThat(order(other, acc, "QGEN", "BUY", 1).status()).isEqualTo(404);

        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE id = ?", acc);
        Response frozen = order(s, acc, "QGEN", "BUY", 1);
        assertThat(frozen.status()).isEqualTo(422);
        assertThat(frozen.jsonBody().get("code").asString()).isEqualTo("ACCOUNT_FROZEN");
        assertThat(Bank.balance(this, s, acc)).isEqualTo("1000.00");
    }

    @Test
    void concurrentSellsNeverOversell() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "10000.00");
        setPrice("WIPR", "100.00");
        order(s, acc, "WIPR", "BUY", 10);

        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> order(s, acc, "WIPR", "SELL", 3).status()));
            }
            for (Future<Integer> f : futures) {
                statuses.add(f.get());
            }
        }
        assertThat(statuses.stream().filter(c -> c == 201).count()).isEqualTo(3);
        assertThat(statuses.stream().filter(c -> c == 422).count()).isEqualTo(5);
        assertThat(get("/api/v1/portfolio", s.accessToken()).jsonBody().get("holdings").get(0).get("quantity").asLong())
                .isEqualTo(1);
        assertThat(Bank.balance(this, s, acc)).isEqualTo("9900.00"); // 10000 − 1000 + 900
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void sameOrderKeyReplays() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "1000.00");
        setPrice("ITCL", "10.00");
        UUID key = UUID.randomUUID();
        Response a = postJson("/api/v1/orders", orderBody(acc, "ITCL", "BUY", 5), Bank.authWithKey(s, key));
        Response b = postJson("/api/v1/orders", orderBody(acc, "ITCL", "BUY", 5), Bank.authWithKey(s, key));
        assertThat(b.header("Idempotent-Replayed")).isEqualTo("true");
        assertThat(b.jsonBody().get("id").asString()).isEqualTo(a.jsonBody().get("id").asString());
        assertThat(Bank.balance(this, s, acc)).isEqualTo("950.00");
    }

    // ------------------------------------------------------------------ websocket

    @Test
    void pricesArePushedToAuthenticatedStompSubscribers() throws Exception {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        WebSocketStompClient client = stompClient();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer " + s.accessToken());
        StompSession session = client.connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), connect,
                new StompSessionHandlerAdapter() { }).get(10, TimeUnit.SECONDS);

        BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
        session.subscribe("/topic/prices", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((JsonNode) payload);
            }
        });
        Thread.sleep(300); // let the SUBSCRIBE reach the broker
        simulator.tick();

        JsonNode prices = received.poll(10, TimeUnit.SECONDS);
        assertThat(prices).isNotNull();
        assertThat(prices.isArray()).isTrue();
        assertThat(prices.size()).isEqualTo(15);
        assertThat(prices.get(0).get("price").isString()).isTrue();
        session.disconnect();
    }

    @Test
    void stompConnectWithoutValidTokenIsRefused() {
        WebSocketStompClient client = stompClient();
        StompHeaders connect = new StompHeaders();
        connect.add("Authorization", "Bearer not-a-token");
        CompletableFuture<StompSession> future = client.connectAsync("ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(), connect, new StompSessionHandlerAdapter() { });
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).isInstanceOfAny(
                java.util.concurrent.ExecutionException.class, java.util.concurrent.TimeoutException.class);
    }

    // ------------------------------------------------------------------ helpers

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new JacksonJsonMessageConverter(json));
        return client;
    }

    private void setPrice(String symbol, String price) {
        jdbc.update("UPDATE instruments SET price = ?::numeric WHERE symbol = ?", price, symbol);
    }

    private Response order(TestUsers.Session s, UUID acc, String symbol, String side, long qty) {
        return postJson("/api/v1/orders", orderBody(acc, symbol, side, qty), Bank.authWithKey(s, UUID.randomUUID()));
    }

    private static Map<String, Object> orderBody(UUID acc, String symbol, String side, long qty) {
        return Map.of("accountId", acc.toString(), "symbol", symbol, "side", side, "quantity", qty);
    }
}
