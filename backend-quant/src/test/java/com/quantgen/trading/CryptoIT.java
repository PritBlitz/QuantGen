package com.quantgen.trading;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.quantgen.support.IntegrationTest;
import com.quantgen.support.TestUsers;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

/** F-TRD-12: crypto prices come from the (fake) feed through the server's cache, behind the crypto flag. */
@TestPropertySource(properties = {"qg.crypto.feed-url=http://127.0.0.1:18093/api/v3", "qg.crypto.poll-millis=3600000",
        "qg.crypto.initial-millis=3600000"})
class CryptoIT extends IntegrationTest {

    static final AtomicInteger feedCalls = new AtomicInteger();
    static HttpServer feed;

    static {
        try {
            feed = HttpServer.create(new InetSocketAddress("127.0.0.1", 18093), 0);
            feed.createContext("/api/v3/simple/price", ex -> {
                feedCalls.incrementAndGet();
                byte[] b = ("{\"bitcoin\":{\"inr\":5803456.789,\"inr_24h_change\":-1.2345,\"last_updated_at\":1761370000},"
                        + "\"ethereum\":{\"inr\":-5},\"dogecoin\":{\"inr\":\"<script>\"},"
                        + "\"evilcoin\":{\"inr\":1}}").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            });
            feed.start();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterAll
    static void stop() {
        feed.stop(0);
    }

    @AfterEach
    void flagOff() {
        jdbc.update("UPDATE feature_flags SET enabled = FALSE WHERE name = 'crypto'");
    }

    @Test
    void pricesAreServedFromTheCacheBehindTheFlagAndOnlySaneOnesSurvive() {
        TestUsers.Session s = TestUsers.registerAndLogin(this);
        Response off = get("/api/v1/market/crypto", s.accessToken());
        assertThat(off.status()).isEqualTo(503);
        assertThat(off.jsonBody().get("code").asString()).isEqualTo("FEATURE_DISABLED");
        assertThat(get("/api/v1/feature-flags", s.accessToken()).jsonBody().has("crypto")).isTrue();

        jdbc.update("UPDATE feature_flags SET enabled = TRUE WHERE name = 'crypto'");
        JsonNode prices = get("/api/v1/market/crypto", s.accessToken()).jsonBody();
        assertThat(prices.size()).isEqualTo(1); // negative, non-numeric and unknown coins are dropped
        assertThat(prices.get(0).get("symbol").asString()).isEqualTo("BTC");
        assertThat(prices.get(0).get("priceInr").asString()).isEqualTo("5803456.79");
        assertThat(prices.get(0).get("change24hPct").asString()).isEqualTo("-1.23");
        assertThat(prices.get(0).get("asOf").asString()).startsWith("2025-10-25");

        int calls = feedCalls.get();
        for (int i = 0; i < 5; i++) {
            get("/api/v1/market/crypto", s.accessToken());
        }
        assertThat(feedCalls.get()).isEqualTo(calls); // customer traffic never reaches the feed
        assertThat(get("/api/v1/market/crypto").status()).isEqualTo(401);
    }
}
