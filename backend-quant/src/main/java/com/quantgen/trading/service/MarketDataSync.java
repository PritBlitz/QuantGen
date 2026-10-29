package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;
import com.quantgen.trading.service.BhavcopyClient.BhavcopyException;
import com.quantgen.trading.service.BhavcopyClient.Row;

/**
 * D-047: keeps real NSE closes current and anchors live prices to them. After 18:30 IST on weekdays (and once at
 * start-up) it walks every calendar day since the last one checked, one request at a time with a pause: a missing
 * file is a holiday (or, for today, not out yet), anything else unexpected stops the run without skipping a day,
 * so the previous-close check below never compares across a gap. When NSE's previous close differs from ours by more
 * than 1%, that is a corporate action (split, bonus, demerger) and its factor back-adjusts older closes.
 * <p>
 * Live prices: the first complete run moves them to the latest real closes in one transaction that also cancels
 * every open order on those symbols and pauses PRICE_CROSSES rules, so the jump fills and fires nothing (the
 * owner's decision, D-047). Later runs re-anchor price and opening price to each new close. A failure here never
 * touches trading: the simulator just keeps walking from the last anchor. Off unless {@code qg.market.sync.enabled}.
 */
@Component
public class MarketDataSync {

    private static final Logger log = LoggerFactory.getLogger(MarketDataSync.class);
    private static final BigDecimal ACTION_THRESHOLD = new BigDecimal("0.01");

    public record Result(int daysStored, LocalDate syncedThrough, boolean rebased, int ordersCancelled, String error) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BhavcopyClient client;
    private final MarketHistory history;
    private final OrderBook orderBook;
    private final AdvisoryLocks locks;
    private final NotificationService notifications;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final boolean enabled;
    private final long pauseMillis;

    public MarketDataSync(JdbcTemplate jdbc, PlatformTransactionManager txManager, BhavcopyClient client,
                          MarketHistory history, OrderBook orderBook, AdvisoryLocks locks,
                          NotificationService notifications, AuditService audit, ApplicationEventPublisher events,
                          Clock clock, @Value("${qg.market.sync.enabled:false}") boolean enabled,
                          @Value("${qg.market.sync.pause-millis:1500}") long pauseMillis) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.client = client;
        this.history = history;
        this.orderBook = orderBook;
        this.locks = locks;
        this.notifications = notifications;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.enabled = enabled;
        this.pauseMillis = pauseMillis;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        if (enabled) {
            Thread.ofVirtual().name("market-sync-backfill").start(this::runLogged);
        }
    }

    @Scheduled(cron = "${qg.market.sync.cron:0 30 18 * * MON-FRI}", zone = "Asia/Kolkata")
    void scheduled() {
        if (enabled) {
            runLogged();
        }
    }

    private void runLogged() {
        try {
            Result r = run();
            if (r != null) {
                log.info("Market sync: {} new day(s), checked through {}, rebased={}, orders cancelled={}{}",
                        r.daysStored(), r.syncedThrough(), r.rebased(), r.ordersCancelled(),
                        r.error() == null ? "" : ", stopped: " + r.error());
            }
        } catch (RuntimeException e) {
            log.warn("Market sync failed: {}", e.toString());
        }
    }

    /** One run; null if another run holds the claim. */
    public Result run() {
        return run(client, pauseMillis);
    }

    Result run(BhavcopyClient source, long pause) {
        if (jdbc.update("UPDATE market_sync_state SET running_since = now() WHERE id = 1 AND (running_since IS NULL "
                + "OR running_since < now() - interval '30 minutes')") != 1) {
            return null;
        }
        try {
            return sync(source, pause);
        } finally {
            jdbc.update("UPDATE market_sync_state SET running_since = NULL WHERE id = 1");
        }
    }

    private Result sync(BhavcopyClient source, long pause) {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate through = Optional.ofNullable(jdbc.queryForObject(
                "SELECT synced_through FROM market_sync_state WHERE id = 1", LocalDate.class))
                .orElse(MarketHistory.lastVendored("INFY").getKey());
        Map<String, String> qgByNse = MarketHistory.NSE_SYMBOLS.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
        int stored = 0;
        String error = null;
        for (LocalDate d = through.plusDays(1); !d.isAfter(today); d = d.plusDays(1)) {
            Optional<List<Row>> rows;
            try {
                rows = source.fetch(d, qgByNse.keySet());
            } catch (BhavcopyException e) {
                error = d + ": " + e.getMessage();
                break;
            }
            sleep(pause);
            if (rows.isEmpty()) {
                if (d.equals(today)) {
                    break; // today's file isn't out yet; check again on the next run
                }
                markThrough(d);
                continue;
            }
            if (rows.get().isEmpty()) {
                error = d + ": NSE's file has none of our symbols";
                break;
            }
            LocalDate day = d;
            tx.executeWithoutResult(s -> store(day, rows.get(), qgByNse));
            stored++;
        }
        if (stored > 0) {
            history.reload();
        }
        LocalDate checked = jdbc.queryForObject("SELECT synced_through FROM market_sync_state WHERE id = 1",
                LocalDate.class);
        if (error != null) {
            return new Result(stored, checked, false, 0, error);
        }
        boolean rebase = jdbc.queryForObject("SELECT rebased_at IS NULL FROM market_sync_state WHERE id = 1",
                Boolean.class);
        if (!rebase && stored == 0) {
            return new Result(0, checked, false, 0, null);
        }
        int cancelled = tx.execute(s -> anchor(latestCloses(), rebase));
        return new Result(stored, checked, rebase, cancelled, null);
    }

    /** One trading day, in its own transaction: closes, any corporate-action factor, and the checked-through date. */
    private void store(LocalDate day, List<Row> rows, Map<String, String> qgByNse) {
        for (Row r : rows) {
            String symbol = qgByNse.get(r.nseSymbol());
            BigDecimal ours = previousClose(symbol, day);
            BigDecimal factor = r.prevClose().divide(ours, MathContext.DECIMAL64);
            if (factor.subtract(BigDecimal.ONE).abs().compareTo(ACTION_THRESHOLD) > 0) {
                jdbc.update("INSERT INTO market_adjustments (symbol, effective_date, factor) VALUES (?, ?, ?) "
                        + "ON CONFLICT DO NOTHING", symbol, day, factor);
                log.info("Corporate action on {} from {}: older closes x {}", symbol, day, factor);
            }
            jdbc.update("""
                    INSERT INTO market_daily_closes (symbol, day, open_price, high, low, close, prev_close, volume)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, symbol, day, r.open(), r.high(), r.low(), r.close(), r.prevClose(), r.volume());
        }
        markThrough(day);
    }

    /** Our last raw close before {@code day}: a fetched one, else the vendored file's last. */
    private BigDecimal previousClose(String symbol, LocalDate day) {
        return jdbc.queryForList("SELECT close FROM market_daily_closes WHERE symbol = ? AND day < ? "
                        + "ORDER BY day DESC LIMIT 1", BigDecimal.class, symbol, day).stream().findFirst()
                .orElseGet(() -> BigDecimal.valueOf(MarketHistory.lastVendored(symbol).getValue()));
    }

    private Map<String, BigDecimal> latestCloses() {
        Map<String, BigDecimal> latest = new TreeMap<>();
        MarketHistory.NSE_SYMBOLS.keySet().forEach(sym -> latest.put(sym,
                BigDecimal.valueOf(MarketHistory.lastVendored(sym).getValue())));
        jdbc.query("SELECT DISTINCT ON (symbol) symbol, close FROM market_daily_closes ORDER BY symbol, day DESC",
                rs -> {
                    latest.put(rs.getString(1), rs.getBigDecimal(2));
                });
        return latest;
    }

    /**
     * Moves live prices to {@code closes}. On the first (rebase) run it also cancels open orders on those symbols
     * and tells listeners, all in this one transaction. Lock order: orders → simulator lock → instruments, the same
     * order-row-first order the matcher uses. Returns how many orders were cancelled.
     */
    private int anchor(Map<String, BigDecimal> closes, boolean rebase) {
        if (rebase) {
            orderBook.lockOpen(closes.keySet());
        }
        locks.lockForTransaction("price-simulator");
        jdbc.queryForList("SELECT symbol FROM instruments WHERE symbol = ANY (?) ORDER BY symbol FOR UPDATE",
                String.class, (Object) closes.keySet().toArray(String[]::new));
        List<OrderBook.Cancelled> cancelled = rebase ? orderBook.cancelOpen(closes.keySet()) : List.of();
        if (rebase) {
            events.publishEvent(new LivePricesRebasedEvent(closes.keySet()));
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.batchUpdate("UPDATE instruments SET price = ?, open_price = ?, updated_at = ?, version = version + 1 "
                + "WHERE symbol = ?", closes.entrySet().stream()
                .map(e -> new Object[] {e.getValue(), e.getValue(), now, e.getKey()}).toList());
        jdbc.batchUpdate("INSERT INTO price_ticks (symbol, price, ts) VALUES (?, ?, ?)", closes.entrySet().stream()
                .map(e -> new Object[] {e.getKey(), e.getValue(), now}).toList());
        if (rebase) {
            jdbc.update("UPDATE market_sync_state SET rebased_at = ? WHERE id = 1", now);
            for (OrderBook.Cancelled c : cancelled) {
                notifications.notify(c.userId(), NotificationType.PRICE_ALERT, "Order cancelled",
                        "Your open " + c.side() + " order for " + c.quantity() + " " + c.symbol() + " was cancelled "
                                + "because live prices moved to real market levels. Money or shares it held are "
                                + "released.");
            }
            Map<String, Long> perSymbol = new HashMap<>();
            cancelled.forEach(c -> perSymbol.merge(c.symbol(), 1L, Long::sum));
            audit.recordAlways(null, "MARKET_PRICES_REBASED", "MARKET", null,
                    "symbols=" + closes.size() + " ordersCancelled=" + cancelled.size() + " " + perSymbol);
        }
        return cancelled.size();
    }

    private void markThrough(LocalDate day) {
        jdbc.update("UPDATE market_sync_state SET synced_through = ? WHERE id = 1 AND (synced_through IS NULL "
                + "OR synced_through < ?)", day, day);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BhavcopyException("interrupted");
        }
    }
}
