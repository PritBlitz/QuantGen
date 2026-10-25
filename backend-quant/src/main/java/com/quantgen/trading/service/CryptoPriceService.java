package com.quantgen.trading.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.flags.FeatureFlagService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-TRD-12 (D-035): live crypto prices in rupees from CoinGecko's free, no-key {@code simple/price}, read-only and
 * behind the {@code crypto} flag. Only the server calls the feed: a poller every minute while the flag is on (plus
 * one lazy fetch, at most every 30 s, while nothing is cached), so customer traffic never reaches the third party.
 * The answer is untrusted input: only the coins below, positive bounded prices. On a failure the last good prices
 * keep being served with their {@code asOf}. Nothing here trades or touches money.
 */
@Service
public class CryptoPriceService {

    private static final Logger log = LoggerFactory.getLogger(CryptoPriceService.class);
    public static final String FLAG = "crypto";
    static final Duration LAZY_FETCH_GAP = Duration.ofSeconds(30);
    static final BigDecimal MAX_PRICE = new BigDecimal("1000000000000");

    /** Feed id → (symbol, name), in display order. */
    static final Map<String, String[]> COINS = new LinkedHashMap<>();

    static {
        COINS.put("bitcoin", new String[] {"BTC", "Bitcoin"});
        COINS.put("ethereum", new String[] {"ETH", "Ethereum"});
        COINS.put("solana", new String[] {"SOL", "Solana"});
        COINS.put("ripple", new String[] {"XRP", "XRP"});
        COINS.put("cardano", new String[] {"ADA", "Cardano"});
        COINS.put("dogecoin", new String[] {"DOGE", "Dogecoin"});
    }

    public record CryptoPriceDto(String symbol, String name, String priceInr, String change24hPct, Instant asOf) {
    }

    private final FeatureFlagService flags;
    private final JsonMapper json;
    private final Clock clock;
    private final String feedUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final AtomicReference<List<CryptoPriceDto>> latest = new AtomicReference<>(List.of());
    private volatile Instant lastAttempt = Instant.EPOCH;

    CryptoPriceService(FeatureFlagService flags, JsonMapper json, Clock clock,
                       @Value("${qg.crypto.feed-url:https://api.coingecko.com/api/v3}") String feedUrl) {
        this.flags = flags;
        this.json = json;
        this.clock = clock;
        this.feedUrl = feedUrl.replaceAll("/+$", "");
    }

    /** The cached prices; 503 FEATURE_DISABLED with the flag off, 503 GATEWAY_UNAVAILABLE if nothing came yet. */
    public List<CryptoPriceDto> prices() {
        if (!flags.isEnabled(FLAG)) {
            throw new ApiException(ErrorCode.FEATURE_DISABLED, "Crypto prices are switched off");
        }
        if (latest.get().isEmpty() && clock.instant().isAfter(lastAttempt.plus(LAZY_FETCH_GAP))) {
            refresh();
        }
        List<CryptoPriceDto> now = latest.get();
        if (now.isEmpty()) {
            throw new ApiException(ErrorCode.GATEWAY_UNAVAILABLE, "Crypto prices aren't available right now");
        }
        return now;
    }

    @Scheduled(fixedDelayString = "${qg.crypto.poll-millis:60000}", initialDelayString = "${qg.crypto.initial-millis:20000}")
    void poll() {
        try {
            if (flags.isEnabled(FLAG)) {
                refresh();
            }
        } catch (RuntimeException e) {
            log.warn("Crypto price poll failed: {}", e.getClass().getSimpleName());
        }
    }

    synchronized void refresh() {
        lastAttempt = clock.instant();
        String url = feedUrl + "/simple/price?ids=" + String.join(",", COINS.keySet())
                + "&vs_currencies=inr&include_24hr_change=true&include_last_updated_at=true";
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200 || res.body().length() > 20_000) {
                log.warn("Crypto feed answered {}", res.statusCode());
                return;
            }
            List<CryptoPriceDto> parsed = parse(json.readTree(res.body()), clock.instant());
            if (!parsed.isEmpty()) {
                latest.set(parsed);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Crypto feed unreachable: {}", e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Only known coins with a sane positive price; anything else in the answer is ignored. */
    static List<CryptoPriceDto> parse(JsonNode body, Instant now) {
        List<CryptoPriceDto> out = new ArrayList<>();
        for (Map.Entry<String, String[]> coin : COINS.entrySet()) {
            JsonNode n = body.path(coin.getKey());
            if (!n.path("inr").isNumber()) {
                continue;
            }
            BigDecimal price = n.path("inr").decimalValue();
            if (price.signum() <= 0 || price.compareTo(MAX_PRICE) >= 0) {
                continue;
            }
            String change = null;
            if (n.path("inr_24h_change").isNumber()) {
                BigDecimal c = n.path("inr_24h_change").decimalValue();
                if (c.compareTo(new BigDecimal("-100")) >= 0 && c.compareTo(new BigDecimal("100000")) <= 0) {
                    change = c.setScale(2, RoundingMode.HALF_UP).toPlainString();
                }
            }
            long updated = n.path("last_updated_at").asLong(0);
            Instant asOf = updated > 0 && updated <= now.getEpochSecond() + 60 ? Instant.ofEpochSecond(updated) : now;
            out.add(new CryptoPriceDto(coin.getValue()[0], coin.getValue()[1],
                    price.setScale(2, RoundingMode.HALF_UP).toPlainString(), change, asOf));
        }
        return out;
    }
}
