package com.quantgen.trading.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.trading.api.TradingDtos.InstrumentDto;

/**
 * F-TRD-2: every few seconds, moves every price one random-walk step, stores the ticks and pushes the
 * new {@code InstrumentDto[]} to {@code /topic/prices}. A Postgres advisory lock makes sure only one
 * instance simulates if several are running.
 */
@Component
public class PriceSimulator {

    public static final String TOPIC = "/topic/prices";
    private static final Logger log = LoggerFactory.getLogger(PriceSimulator.class);
    private static final Duration TICK_RETENTION = Duration.ofDays(7);

    private final JdbcTemplate jdbc;
    private final MarketService market;
    private final SimpMessagingTemplate messaging;
    private final AdvisoryLocks locks;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    private final Random random = new Random();

    public PriceSimulator(JdbcTemplate jdbc, MarketService market, SimpMessagingTemplate messaging, AdvisoryLocks locks,
                          PlatformTransactionManager txManager, Clock clock,
                          @Value("${qg.market.simulator-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.market = market;
        this.messaging = messaging;
        this.locks = locks;
        this.tx = new TransactionTemplate(txManager);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Scheduled(fixedRateString = "${qg.market.tick-millis:3000}", initialDelayString = "${qg.market.tick-millis:3000}")
    void scheduledTick() {
        if (!enabled) {
            return;
        }
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Price tick failed", e);
        }
    }

    /** Runs one tick and broadcasts it. Returns the new prices, or an empty list if another instance holds the lock. */
    public List<InstrumentDto> tick() {
        List<InstrumentDto> prices = tx.execute(status -> {
            if (!locks.tryLockForTransaction("price-simulator")) {
                return List.<InstrumentDto>of();
            }
            Instant now = clock.instant();
            List<Object[]> moves = jdbc.query("SELECT symbol, price, open_price FROM instruments ORDER BY symbol FOR UPDATE",
                    (rs, i) -> new Object[] {rs.getString(1),
                            PriceWalk.next(rs.getBigDecimal(2), rs.getBigDecimal(3), random.nextGaussian())});
            Timestamp ts = Timestamp.from(now);
            jdbc.batchUpdate("UPDATE instruments SET price = ?, updated_at = ?, version = version + 1 WHERE symbol = ?",
                    moves.stream().map(m -> new Object[] {m[1], ts, m[0]}).toList());
            jdbc.batchUpdate("INSERT INTO price_ticks (symbol, price, ts) VALUES (?, ?, ?)",
                    moves.stream().map(m -> new Object[] {m[0], m[1], ts}).toList());
            return market.list();
        });
        if (prices != null && !prices.isEmpty()) {
            messaging.convertAndSend(TOPIC, prices);
        }
        return prices == null ? List.of() : prices;
    }

    /** Keeps 7 days of ticks (ARCHITECTURE §6). */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Kolkata")
    void pruneOldTicks() {
        int deleted = jdbc.update("DELETE FROM price_ticks WHERE ts < ?", Timestamp.from(clock.instant().minus(TICK_RETENTION)));
        log.info("Pruned {} price ticks older than {}", deleted, TICK_RETENTION);
    }
}
