package com.quantgen.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.ledger.service.LedgerService;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;
import com.quantgen.trading.service.OrderBook;
import com.quantgen.trading.service.WatchlistService;

import tools.jackson.databind.JsonNode;

/** V2-M7 Trading 2.0: limit/stop orders (exit test: fills correct under concurrent ticks and cancels), watchlists,
 * price alerts, backtests, tax P&L. Prices are set directly; the simulator is off in tests. */
class Trading2IT extends IntegrationTest {

    @Autowired
    OrderBook orderBook;

    @Autowired
    WatchlistService watchlists;

    @Autowired
    LedgerService ledger;

    private void price(String symbol, String price) {
        jdbc.update("UPDATE instruments SET price = ? WHERE symbol = ?", new BigDecimal(price), symbol);
    }

    private Response order(TestUsers.Session s, UUID account, String symbol, String side, long qty, Map<String, Object> extra) {
        Map<String, Object> body = new HashMap<>(Map.of("accountId", account.toString(), "symbol", symbol, "side", side,
                "quantity", qty));
        body.putAll(extra);
        return postJson("/api/v1/orders", body, Bank.authWithKey(s, UUID.randomUUID()));
    }

    private JsonNode okOrder(TestUsers.Session s, UUID account, String symbol, String side, long qty,
                             Map<String, Object> extra) {
        Response r = order(s, account, symbol, side, qty, extra);
        assertThat(r.status()).as(r.body()).isEqualTo(201);
        return r.jsonBody();
    }

    private String available(TestUsers.Session s, UUID account) {
        return get("/api/v1/accounts/" + account, s.accessToken()).jsonBody().get("availableBalance").asString();
    }

    private long held(TestUsers.Session s, String symbol) {
        for (JsonNode h : get("/api/v1/portfolio", s.accessToken()).jsonBody().get("holdings")) {
            if (h.get("symbol").asString().equals(symbol)) {
                return h.get("quantity").asLong();
            }
        }
        return 0;
    }

    @Test
    void orderTermsAreValidated() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "TRAILING")).status()).isEqualTo(400);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "LIMIT")).status()).isEqualTo(400);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "STOP_LOSS", "triggerPrice", "10.00")).status())
                .isEqualTo(400);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("limitPrice", "10.00")).status()).isEqualTo(400);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "LIMIT", "limitPrice", "-1.00")).status())
                .isEqualTo(400);
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "LIMIT", "limitPrice", "10.00",
                "validity", "WEEK")).status()).isEqualTo(400);
        // A buy limit the account can't cover is refused up front (the hold can't be placed).
        price("TCS", "4000.00");
        assertThat(order(alice, acc, "TCS", "BUY", 1, Map.of("orderType", "LIMIT", "limitPrice", "3000.00"))
                .jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @Test
    void limitBuyHoldsCashAndFillsAtTheLimit() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "50000.00");
        price("INFY", "1500.00");

        JsonNode open = okOrder(alice, acc, "INFY", "BUY", 10, Map.of("orderType", "LIMIT", "limitPrice", "1400.00",
                "validity", "GTC"));
        assertThat(open.get("status").asString()).isEqualTo("OPEN");
        assertThat(open.get("orderType").asString()).isEqualTo("LIMIT");
        assertThat(open.get("limitPrice").asString()).isEqualTo("1400.00");
        assertThat(open.get("price").asString()).isEqualTo("1400.00");
        assertThat(open.get("total").asString()).isEqualTo("14000.00");
        assertThat(open.get("validity").asString()).isEqualTo("GTC");
        assertThat(open.get("filledAt").isNull()).isTrue();
        assertThat(available(alice, acc)).isEqualTo("36000.00");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("50000.00");

        orderBook.matchAll();                       // 1500 > 1400: nothing
        price("INFY", "1390.00");
        assertThat(orderBook.matchAll()).isGreaterThanOrEqualTo(1);
        JsonNode filled = get("/api/v1/orders", alice.accessToken()).jsonBody().get("content").get(0);
        assertThat(filled.get("status").asString()).isEqualTo("FILLED");
        assertThat(filled.get("price").asString()).isEqualTo("1400.00"); // at the limit price
        assertThat(filled.get("filledAt").isNull()).isFalse();
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("36000.00");
        assertThat(available(alice, acc)).isEqualTo("36000.00"); // hold released, cash spent once
        assertThat(held(alice, "INFY")).isEqualTo(10);

        // Marketable limit: fills immediately at the current (better) price.
        JsonNode now = okOrder(alice, acc, "INFY", "BUY", 1, Map.of("orderType", "LIMIT", "limitPrice", "1450.00"));
        assertThat(now.get("status").asString()).isEqualTo("FILLED");
        assertThat(now.get("price").asString()).isEqualTo("1390.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void openSellsReserveSharesAndCancelReleasesThem() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "20000.00");
        price("TCS", "1000.00");
        okOrder(alice, acc, "TCS", "BUY", 10, Map.of());

        JsonNode sell = okOrder(alice, acc, "TCS", "SELL", 6, Map.of("orderType", "LIMIT", "limitPrice", "1200.00"));
        assertThat(sell.get("status").asString()).isEqualTo("OPEN");
        Response oversell = order(alice, acc, "TCS", "SELL", 5, Map.of());
        assertThat(oversell.status()).isEqualTo(422);
        assertThat(oversell.jsonBody().get("code").asString()).isEqualTo("INSUFFICIENT_HOLDINGS");
        assertThat(order(alice, acc, "TCS", "SELL", 5, Map.of("orderType", "STOP_LOSS", "triggerPrice", "900.00"))
                .status()).isEqualTo(422);

        String cancelPath = "/api/v1/orders/" + sell.get("id").asString() + "/cancel";
        assertThat(postJson(cancelPath, Map.of(), Bank.auth(bob)).status()).isEqualTo(404);
        JsonNode cancelled = postJson(cancelPath, Map.of(), Bank.auth(alice)).jsonBody();
        assertThat(cancelled.get("status").asString()).isEqualTo("CANCELLED");
        assertThat(postJson(cancelPath, Map.of(), Bank.auth(alice)).status()).isEqualTo(409);
        assertThat(okOrder(alice, acc, "TCS", "SELL", 10, Map.of()).get("status").asString()).isEqualTo("FILLED");
        assertThat(held(alice, "TCS")).isZero();
    }

    @Test
    void stopLossSellsAtTheTickPriceAndOrdersExpire() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "20000.00");
        price("WIPR", "500.00");
        okOrder(alice, acc, "WIPR", "BUY", 10, Map.of());                     // 5000 spent
        JsonNode stop = okOrder(alice, acc, "WIPR", "SELL", 10, Map.of("orderType", "STOP_LOSS",
                "triggerPrice", "450.00"));
        assertThat(stop.get("status").asString()).isEqualTo("OPEN");
        assertThat(stop.get("validity").asString()).isEqualTo("DAY");
        price("WIPR", "430.00");
        orderBook.matchAll();
        JsonNode filled = get("/api/v1/orders", alice.accessToken()).jsonBody().get("content").get(0);
        assertThat(filled.get("status").asString()).isEqualTo("FILLED");
        assertThat(filled.get("price").asString()).isEqualTo("430.00");
        assertThat(Bank.balance(this, alice, acc)).isEqualTo("19300.00");    // 15000 + 4300

        JsonNode buy = okOrder(alice, acc, "WIPR", "BUY", 5, Map.of("orderType", "LIMIT", "limitPrice", "100.00"));
        assertThat(available(alice, acc)).isEqualTo("18800.00");
        jdbc.update("UPDATE orders SET expires_at = ? WHERE id = ?::uuid", Timestamp.from(Instant.now().minusSeconds(1)),
                buy.get("id").asString());
        assertThat(orderBook.expireDue()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM orders WHERE id = ?::uuid", String.class,
                buy.get("id").asString())).isEqualTo("EXPIRED");
        assertThat(available(alice, acc)).isEqualTo("19300.00");
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void concurrentTicksAndCancelsFillOrCancelEachOrderExactlyOnce() throws Exception {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "100000.00");
        price("HDFC", "1000.00");
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(okOrder(alice, acc, "HDFC", "BUY", 5, Map.of("orderType", "LIMIT", "limitPrice", "900.00"))
                    .get("id").asString());
        }
        assertThat(available(alice, acc)).isEqualTo("64000.00");
        price("HDFC", "880.00");
        List<CompletableFuture<?>> work = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            work.add(CompletableFuture.runAsync(() -> orderBook.matchAll()));
        }
        for (String id : ids) {
            work.add(CompletableFuture.runAsync(() -> postJson("/api/v1/orders/" + id + "/cancel", Map.of(),
                    Bank.auth(alice))));
        }
        CompletableFuture.allOf(work.toArray(new CompletableFuture[0])).get();
        orderBook.matchAll(); // anything left OPEN by a skipped lock fills now

        int filled = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = (SELECT user_id FROM accounts "
                + "WHERE id = ?) AND status = 'FILLED'", Integer.class, acc);
        int cancelled = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = (SELECT user_id FROM accounts "
                + "WHERE id = ?) AND status = 'CANCELLED'", Integer.class, acc);
        assertThat(filled + cancelled).isEqualTo(8);
        BigDecimal spent = new BigDecimal("4500.00").multiply(BigDecimal.valueOf(filled));
        assertThat(new BigDecimal(Bank.balance(this, alice, acc)))
                .isEqualByComparingTo(new BigDecimal("100000.00").subtract(spent));
        assertThat(available(alice, acc)).isEqualTo(Bank.balance(this, alice, acc)); // no hold left behind
        assertThat(held(alice, "HDFC")).isEqualTo(5L * filled);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holds WHERE ledger_account_id = ? AND status = 'ACTIVE'",
                Integer.class, acc)).isZero();
        assertThat(ledger.reconcile().balanced()).isTrue();
    }

    @Test
    void watchlistsAndPriceAlerts() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        TestUsers.Session bob = TestUsers.registerAndLogin(this);
        JsonNode w = postJson("/api/v1/watchlists", Map.of("name", "Tech"), Bank.auth(alice)).jsonBody();
        String path = "/api/v1/watchlists/" + w.get("id").asString();
        assertThat(postJson(path + "/symbols/tcs", Map.of(), Bank.auth(alice)).jsonBody().get("symbols").get(0).asString())
                .isEqualTo("TCS");
        postJson(path + "/symbols/TCS", Map.of(), Bank.auth(alice)); // already there: no-op
        assertThat(postJson(path + "/symbols/INFY", Map.of(), Bank.auth(alice)).jsonBody().get("symbols").size())
                .isEqualTo(2);
        assertThat(postJson(path + "/symbols/NOPE", Map.of(), Bank.auth(alice)).status()).isEqualTo(400);
        assertThat(postJson(path + "/symbols/INFY", Map.of(), Bank.auth(bob)).status()).isEqualTo(404);
        assertThat(delete(path + "/symbols/INFY", alice.accessToken()).jsonBody().get("symbols").size()).isEqualTo(1);
        for (int i = 2; i <= 5; i++) {
            assertThat(postJson("/api/v1/watchlists", Map.of("name", "L" + i), Bank.auth(alice)).status()).isEqualTo(201);
        }
        assertThat(postJson("/api/v1/watchlists", Map.of("name", "L6"), Bank.auth(alice)).status()).isEqualTo(422);
        assertThat(delete(path, bob.accessToken()).status()).isEqualTo(404);
        assertThat(delete(path, alice.accessToken()).status()).isEqualTo(204);
        assertThat(get("/api/v1/watchlists", alice.accessToken()).jsonBody().size()).isEqualTo(4);

        price("ITCL", "400.00");
        JsonNode alert = postJson("/api/v1/price-alerts", Map.of("symbol", "ITCL", "direction", "ABOVE", "price", "420.00"),
                Bank.auth(alice)).jsonBody();
        assertThat(alert.get("status").asString()).isEqualTo("ACTIVE");
        postJson("/api/v1/price-alerts", Map.of("symbol", "ITCL", "direction", "BELOW", "price", "300.00"), Bank.auth(alice));
        watchlists.fireAlerts();
        price("ITCL", "425.00");
        watchlists.fireAlerts();
        watchlists.fireAlerts();
        JsonNode alerts = get("/api/v1/price-alerts", alice.accessToken()).jsonBody();
        int triggered = 0;
        for (JsonNode a : alerts) {
            if (a.get("status").asString().equals("TRIGGERED")) {
                triggered++;
                assertThat(a.get("direction").asString()).isEqualTo("ABOVE");
                assertThat(a.get("triggeredAt").isNull()).isFalse();
            }
        }
        assertThat(triggered).isEqualTo(1);
        int notes = 0;
        for (JsonNode n : get("/api/v1/notifications", alice.accessToken()).jsonBody()) {
            if (n.get("type").asString().equals("PRICE_ALERT")) {
                notes++;
            }
        }
        assertThat(notes).isEqualTo(1);
        assertThat(delete("/api/v1/price-alerts/" + alert.get("id").asString(), bob.accessToken()).status()).isEqualTo(404);
        assertThat(postJson("/api/v1/price-alerts", Map.of("symbol", "ITCL", "direction", "SIDEWAYS", "price", "1.00"),
                Bank.auth(alice)).status()).isEqualTo(400);
    }

    @Test
    void backtestsAreDeterministicAndValidated() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        Map<String, Object> req = Map.of("strategy", Map.of("type", "SMA_CROSS", "fast", 10, "slow", 30),
                "symbols", List.of("TCS", "INFY"), "from", "2024-01-01", "to", "2024-12-31", "initialCash", "100000.00");
        Response first = postJson("/api/v1/backtests", req, Bank.auth(alice));
        assertThat(first.status()).as(first.body()).isEqualTo(201);
        JsonNode a = first.jsonBody();
        JsonNode b = postJson("/api/v1/backtests", req, Bank.auth(alice)).jsonBody();
        assertThat(a.get("finalValue").asString()).isEqualTo(b.get("finalValue").asString());
        assertThat(a.get("equity").size()).isBetween(2, 400);
        assertThat(a.get("equity").get(0).get("value").asString()).isEqualTo("100000.00");
        assertThat(a.get("strategy").get("type").asString()).isEqualTo("SMA_CROSS");
        assertThat(get("/api/v1/backtests", alice.accessToken()).jsonBody().size()).isEqualTo(2);

        Response rebalance = postJson("/api/v1/backtests", Map.of("strategy", Map.of("type", "REBALANCE", "everyDays", 30,
                        "weights", Map.of("TCS", "60", "INFY", "40")), "symbols", List.of("TCS", "INFY"), "from",
                "2023-01-01", "to", "2023-06-30", "initialCash", "50000.00"), Bank.auth(alice));
        assertThat(rebalance.status()).as(rebalance.body()).isEqualTo(201);
        assertThat(rebalance.jsonBody().get("trades").size()).isGreaterThan(0);

        assertThat(postJson("/api/v1/backtests", Map.of("strategy", Map.of("type", "SMA_CROSS", "fast", 30, "slow", 10),
                "symbols", List.of("TCS"), "from", "2024-01-01", "to", "2024-12-31", "initialCash", "1000.00"),
                Bank.auth(alice)).status()).isEqualTo(400);
        assertThat(postJson("/api/v1/backtests", Map.of("strategy", Map.of("type", "RSI", "period", 14, "buyBelow", 30,
                "sellAbove", 70), "symbols", List.of("TCS"), "from", "2019-01-01", "to", "2024-12-31", "initialCash",
                "1000.00"), Bank.auth(alice)).status()).isEqualTo(400);
        assertThat(postJson("/api/v1/backtests", Map.of("strategy", Map.of("type", "MAGIC"), "symbols", List.of("TCS"),
                "from", "2024-01-01", "to", "2024-12-31", "initialCash", "1000.00"), Bank.auth(alice)).status())
                .isEqualTo(400);
    }

    @Test
    void taxPnlMatchesLotsFirstInFirstOut() {
        TestUsers.Session alice = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, alice);
        Bank.deposit(this, alice, acc, "50000.00");
        price("MARU", "100.00");
        String old = okOrder(alice, acc, "MARU", "BUY", 10, Map.of()).get("id").asString();  // cost 1000
        price("MARU", "200.00");
        okOrder(alice, acc, "MARU", "BUY", 10, Map.of());                                      // cost 2000
        // The first lot was bought 13 months ago.
        jdbc.update("UPDATE orders SET filled_at = filled_at - interval '13 months' WHERE id = ?::uuid", old);
        price("MARU", "300.00");
        okOrder(alice, acc, "MARU", "SELL", 15, Map.of());                                     // proceeds 4500

        String fy = TaxFy.of(LocalDate.now(com.quantgen.common.config.TimeConfig.BUSINESS_ZONE));
        JsonNode r = get("/api/v1/reports/tax-pnl?fy=" + fy, alice.accessToken()).jsonBody();
        JsonNode rows = r.get("rows");
        assertThat(rows.size()).isEqualTo(2);
        assertThat(rows.get(0).get("quantity").asLong()).isEqualTo(10);
        assertThat(rows.get(0).get("term").asString()).isEqualTo("LONG");
        assertThat(rows.get(0).get("cost").asString()).isEqualTo("1000.00");
        assertThat(rows.get(0).get("proceeds").asString()).isEqualTo("3000.00");
        assertThat(rows.get(1).get("quantity").asLong()).isEqualTo(5);
        assertThat(rows.get(1).get("term").asString()).isEqualTo("SHORT");
        assertThat(rows.get(1).get("cost").asString()).isEqualTo("1000.00");
        assertThat(rows.get(1).get("gain").asString()).isEqualTo("500.00");
        assertThat(r.get("longTermGains").asString()).isEqualTo("2000.00");
        assertThat(r.get("shortTermGains").asString()).isEqualTo("500.00");
        assertThat(r.get("total").asString()).isEqualTo("2500.00");

        Response csv = get("/api/v1/reports/tax-pnl?fy=" + fy + "&format=csv", alice.accessToken());
        assertThat(csv.status()).isEqualTo(200);
        assertThat(csv.body()).startsWith("symbol,buyDate,sellDate,quantity,cost,proceeds,gain,term")
                .contains("MARU").contains("TOTAL,,,,,,2500.00,");
        assertThat(get("/api/v1/reports/tax-pnl?fy=2026-28", alice.accessToken()).status()).isEqualTo(400);
        assertThat(get("/api/v1/reports/tax-pnl?format=pdf", alice.accessToken()).status()).isEqualTo(400);
    }

    /** Financial year label, as the report computes it. */
    private static final class TaxFy {
        static String of(LocalDate d) {
            int start = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
            return start + "-" + String.format("%02d", (start + 1) % 100);
        }
    }
}
