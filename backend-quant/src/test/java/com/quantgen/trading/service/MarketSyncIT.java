package com.quantgen.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.support.Bank;
import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;
import com.sun.net.httpserver.HttpServer;

/**
 * D-047 end to end against a fake NSE: back-fill across a holiday, a 1:1 bonus back-adjusts history, the first
 * complete run moves live prices to real levels without filling or firing anything, later runs re-anchor.
 */
class MarketSyncIT extends IntegrationTest {

    @Autowired
    MarketDataSync sync;
    @Autowired
    MarketHistory history;

    final Map<String, byte[]> files = new ConcurrentHashMap<>();
    HttpServer nse;
    BhavcopyClient client;
    Map<String, BigDecimal[]> pricesBefore;

    @BeforeEach
    void fakeNse() throws IOException {
        nse = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        nse.createContext("/", ex -> {
            String name = ex.getRequestURI().getPath().substring(1);
            byte[] body = files.get(name);
            ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
            if (body != null) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(body);
                }
            }
            ex.close();
        });
        nse.start();
        client = new BhavcopyClient("http://127.0.0.1:" + nse.getAddress().getPort() + "/");
        pricesBefore = new HashMap<>();
        jdbc.query("SELECT symbol, price, open_price FROM instruments", rs -> {
            pricesBefore.put(rs.getString(1), new BigDecimal[] {rs.getBigDecimal(2), rs.getBigDecimal(3)});
        });
    }

    /** Leaves the shared test database as it found it. */
    @AfterEach
    void restore() {
        nse.stop(0);
        jdbc.update("DELETE FROM market_daily_closes");
        jdbc.update("DELETE FROM market_adjustments");
        jdbc.update("UPDATE market_sync_state SET synced_through = NULL, running_since = NULL, rebased_at = NULL");
        pricesBefore.forEach((sym, p) -> jdbc.update("UPDATE instruments SET price = ?, open_price = ? WHERE symbol = ?",
                p[0], p[1], sym));
        jdbc.update("UPDATE autopilot_rules SET status = 'DELETED', paused_reason = NULL WHERE name = 'MarketSyncIT'");
        history.reload();
    }

    private void serve(LocalDate day, String... lines) throws IOException {
        String csv = BhavcopyClientTest.HEADER + "\n" + String.join("\n", lines);
        files.put(BhavcopyClient.fileName(day) + ".zip", BhavcopyClientTest.zip(BhavcopyClient.fileName(day), csv));
    }

    private static String infy(LocalDate day, String close, String prev) {
        return BhavcopyClientTest.line(day, "INFY", "EQ", close, close, close, close, prev);
    }

    private String status(String orderId) {
        return jdbc.queryForObject("SELECT status FROM orders WHERE id = ?::uuid", String.class, orderId);
    }

    private BigDecimal price(String symbol) {
        return jdbc.queryForObject("SELECT price FROM instruments WHERE symbol = ?", BigDecimal.class, symbol);
    }

    @Test
    void backfillsAdjustsForCorporateActionsAndRebasesWithoutFiringAnything() throws Exception {
        LocalDate today = LocalDate.now(java.time.Clock.system(TimeConfig.BUSINESS_ZONE));
        LocalDate fri = LocalDate.of(2026, 1, 23);   // the day after the vendored file ends (INFY 1663.50)
        LocalDate tue = LocalDate.of(2026, 1, 27);   // Monday the 26th is Republic Day: no file
        serve(fri, infy(fri, "1670.00", "1663.50"));
        serve(tue, infy(tue, "840.00", "835.00"));   // NSE's previous close is 1670 / 2: a 1:1 bonus

        // Someone holds an open limit buy on INFY and a PRICE_CROSSES rule drawn at the old simulated level.
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        UUID acc = Bank.firstAccountId(this, s);
        Bank.deposit(this, s, acc, "10000.00");
        Response placed = postJson("/api/v1/orders", Map.of("accountId", acc.toString(), "symbol", "INFY", "side", "BUY",
                "quantity", 2, "orderType", "LIMIT", "limitPrice", "1000.00"), Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(placed.status()).as(placed.body()).isEqualTo(201);
        String orderId = placed.jsonBody().get("id").asString();
        UUID rule = jdbc.queryForObject("""
                INSERT INTO autopilot_rules (user_id, name, version, status, trigger_type)
                VALUES (?, 'MarketSyncIT', 1, 'ACTIVE', 'PRICE_CROSSES') RETURNING id""", UUID.class, s.userId());
        jdbc.update("INSERT INTO autopilot_rule_versions (rule_id, version, dsl) VALUES (?, 1, ?)", rule,
                "{\"dslVersion\":1,\"trigger\":{\"type\":\"PRICE_CROSSES\",\"symbol\":\"INFY\",\"direction\":\"BELOW\","
                        + "\"price\":\"1400.00\"},\"conditions\":[],\"actions\":[{\"type\":\"NOTIFY\",\"message\":\"x\"}]}");

        MarketDataSync.Result r = sync.run(client, 0);
        assertThat(r.error()).isNull();
        assertThat(r.daysStored()).isEqualTo(2);
        assertThat(r.syncedThrough()).isEqualTo(today.minusDays(1)); // today has no file yet: checked again later
        assertThat(r.rebased()).isTrue();
        assertThat(r.ordersCancelled()).isPositive(); // ours, plus any other test's leftovers on these symbols

        // History: the bonus halves everything before it, so backtests see no fake crash.
        assertThat(jdbc.queryForObject("SELECT factor FROM market_adjustments WHERE symbol = 'INFY'", BigDecimal.class))
                .isEqualByComparingTo("0.5");
        assertThat(history.close("INFY", LocalDate.of(2026, 1, 22))).isEqualTo(831.75);
        assertThat(history.close("INFY", fri)).isEqualTo(835.0);
        assertThat(history.close("INFY", tue)).isEqualTo(840.0);

        // Live prices are real now; nothing filled or fired on the jump.
        assertThat(price("INFY")).isEqualByComparingTo("840.00");
        assertThat(price("WIPR")).isEqualByComparingTo("240.65");                 // the vendored last close
        assertThat(price("QGEN")).isEqualByComparingTo(pricesBefore.get("QGEN")[0]); // fictional: untouched
        assertThat(status(orderId)).isEqualTo("CANCELLED");
        assertThat(get("/api/v1/accounts/" + acc, s.accessToken()).jsonBody().get("availableBalance").asString())
                .isEqualTo("10000.00"); // the hold was released
        assertThat(jdbc.queryForObject("SELECT status FROM autopilot_rules WHERE id = ?", String.class, rule))
                .isEqualTo("PAUSED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type IN "
                + "('PRICE_ALERT', 'RULE_PAUSED')", Long.class, s.userId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'MARKET_PRICES_REBASED'",
                Long.class)).isGreaterThanOrEqualTo(1);

        // Nothing new: no second rebase. Today's file arrives: price and opening price re-anchor, orders stay open.
        assertThat(sync.run(client, 0).rebased()).isFalse();
        Response again = postJson("/api/v1/orders", Map.of("accountId", acc.toString(), "symbol", "INFY", "side", "BUY",
                "quantity", 1, "orderType", "LIMIT", "limitPrice", "500.00"), Bank.authWithKey(s, UUID.randomUUID()));
        assertThat(again.status()).as(again.body()).isEqualTo(201);
        serve(today, infy(today, "850.00", "840.00"));
        MarketDataSync.Result next = sync.run(client, 0);
        assertThat(next.daysStored()).isEqualTo(1);
        assertThat(next.rebased()).isFalse();
        assertThat(price("INFY")).isEqualByComparingTo("850.00");
        assertThat(jdbc.queryForObject("SELECT open_price FROM instruments WHERE symbol = 'INFY'", BigDecimal.class))
                .isEqualByComparingTo("850.00");
        assertThat(status(again.jsonBody().get("id").asString())).isEqualTo("OPEN");
        assertThat(postJson("/api/v1/orders/" + again.jsonBody().get("id").asString() + "/cancel", "", Bank.auth(s))
                .status()).isEqualTo(200);
    }

    @Test
    void aFailedFetchStopsWithoutSkippingADayOrTouchingPrices() {
        files.put(BhavcopyClient.fileName(LocalDate.of(2026, 1, 23)) + ".zip", new byte[] {1, 2, 3}); // not a zip
        MarketDataSync.Result r = sync.run(client, 0);
        assertThat(r.error()).contains("2026-01-23");
        assertThat(r.rebased()).isFalse();
        assertThat(r.syncedThrough()).isNull(); // the next run starts at the same day again
        assertThat(price("INFY")).isEqualByComparingTo(pricesBefore.get("INFY")[0]);
        assertThat(List.of(jdbc.queryForObject("SELECT count(*) FROM market_daily_closes", Long.class))).containsExactly(0L);
    }
}
