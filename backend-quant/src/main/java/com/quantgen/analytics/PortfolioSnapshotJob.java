package com.quantgen.analytics;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.quantgen.common.config.TimeConfig;

/**
 * F-ANL-15: writes each investor's end-of-day market value and cost basis, because price ticks are pruned after
 * 7 days and history cannot be rebuilt later. Idempotent per (user, day), so running it twice is harmless.
 */
@Component
public class PortfolioSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotJob.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public PortfolioSnapshotJob(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Scheduled(cron = "0 55 23 * * *", zone = "Asia/Kolkata")
    public void nightly() {
        try {
            int n = snapshot(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
            log.info("Portfolio snapshots written for {} investor(s)", n);
        } catch (RuntimeException e) {
            log.error("Portfolio snapshot job failed", e);
        }
    }

    /** Upserts a snapshot of every user with a position; returns how many rows were written. */
    public int snapshot(LocalDate day) {
        return jdbc.update("""
                INSERT INTO portfolio_snapshots (user_id, day, market_value, invested)
                SELECT h.user_id, ?, SUM(h.quantity * i.price), SUM(h.cost_basis)
                  FROM holdings h JOIN instruments i ON i.symbol = h.symbol
                 WHERE h.quantity > 0 GROUP BY h.user_id
                ON CONFLICT (user_id, day) DO UPDATE
                   SET market_value = EXCLUDED.market_value, invested = EXCLUDED.invested
                """, java.sql.Date.valueOf(day));
    }
}
