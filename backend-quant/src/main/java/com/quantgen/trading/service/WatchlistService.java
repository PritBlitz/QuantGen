package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.db.AdvisoryLocks;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.money.Money;
import com.quantgen.common.notification.NotificationService;
import com.quantgen.common.notification.NotificationType;

/** F-TRD-8 watchlists (up to 5, 50 symbols each) and one-shot price alerts (up to 20 active). */
@Service
public class WatchlistService {

    public static final int MAX_WATCHLISTS = 5;
    public static final int MAX_SYMBOLS = 50;
    public static final int MAX_ACTIVE_ALERTS = 20;

    public enum Direction { ABOVE, BELOW }

    public record WatchlistDto(UUID id, String name, List<String> symbols, Instant createdAt) {
    }

    public record PriceAlertDto(UUID id, String symbol, Direction direction, BigDecimal price, String status,
                                Instant triggeredAt, Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final AdvisoryLocks locks;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public WatchlistService(JdbcTemplate jdbc, AdvisoryLocks locks, NotificationService notifications, Clock clock,
                            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.notifications = notifications;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    // ------------------------------------------------------------------ watchlists

    @Transactional(readOnly = true)
    public List<WatchlistDto> list(UUID userId) {
        Map<UUID, WatchlistDto> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT w.id, w.name, w.created_at, s.symbol FROM watchlists w
                  LEFT JOIN watchlist_symbols s ON s.watchlist_id = w.id
                 WHERE w.user_id = ? ORDER BY w.created_at, w.id, s.added_at, s.symbol
                """, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            WatchlistDto w = out.computeIfAbsent(id, k -> {
                try {
                    return new WatchlistDto(id, rs.getString("name"), new ArrayList<>(),
                            rs.getTimestamp("created_at").toInstant());
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            });
            String symbol = rs.getString("symbol");
            if (symbol != null) {
                w.symbols().add(symbol);
            }
        }, userId);
        return List.copyOf(out.values());
    }

    @Transactional
    public WatchlistDto create(UUID userId, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > 40) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "name", "must be 1-40 characters");
        }
        locks.lockForTransaction("watchlists:" + userId);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM watchlists WHERE user_id = ?", Integer.class, userId);
        if (n != null && n >= MAX_WATCHLISTS) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can have at most " + MAX_WATCHLISTS + " watchlists");
        }
        UUID id = jdbc.queryForObject("INSERT INTO watchlists (user_id, name) VALUES (?, ?) RETURNING id", UUID.class,
                userId, name);
        return one(userId, id);
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        if (jdbc.update("DELETE FROM watchlists WHERE id = ? AND user_id = ?", id, userId) == 0) {
            throw ApiException.notFound("Watchlist");
        }
    }

    @Transactional
    public WatchlistDto addSymbol(UUID userId, UUID id, String rawSymbol) {
        owned(userId, id);
        String symbol = MarketService.normalize(rawSymbol);
        Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM instruments WHERE symbol = ?)", Boolean.class,
                symbol);
        if (!Boolean.TRUE.equals(known)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbol", "unknown instrument");
        }
        jdbc.queryForObject("SELECT id FROM watchlists WHERE id = ? FOR UPDATE", UUID.class, id);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM watchlist_symbols WHERE watchlist_id = ?", Integer.class, id);
        Boolean present = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM watchlist_symbols WHERE watchlist_id = ? "
                + "AND symbol = ?)", Boolean.class, id, symbol);
        if (!Boolean.TRUE.equals(present) && n != null && n >= MAX_SYMBOLS) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "A watchlist holds at most " + MAX_SYMBOLS + " symbols");
        }
        jdbc.update("INSERT INTO watchlist_symbols (watchlist_id, symbol) VALUES (?, ?) ON CONFLICT DO NOTHING", id,
                symbol);
        return one(userId, id);
    }

    @Transactional
    public WatchlistDto removeSymbol(UUID userId, UUID id, String rawSymbol) {
        owned(userId, id);
        jdbc.update("DELETE FROM watchlist_symbols WHERE watchlist_id = ? AND symbol = ?", id,
                MarketService.normalize(rawSymbol));
        return one(userId, id);
    }

    private void owned(UUID userId, UUID id) {
        Boolean mine = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM watchlists WHERE id = ? AND user_id = ?)",
                Boolean.class, id, userId);
        if (!Boolean.TRUE.equals(mine)) {
            throw ApiException.notFound("Watchlist");
        }
    }

    private WatchlistDto one(UUID userId, UUID id) {
        return list(userId).stream().filter(w -> w.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Watchlist"));
    }

    // ------------------------------------------------------------------ price alerts

    @Transactional(readOnly = true)
    public List<PriceAlertDto> alerts(UUID userId) {
        return jdbc.query("SELECT * FROM price_alerts WHERE user_id = ? ORDER BY created_at DESC, id", (rs, i) -> {
            Timestamp t = rs.getTimestamp("triggered_at");
            return new PriceAlertDto(rs.getObject("id", UUID.class), rs.getString("symbol"),
                    Direction.valueOf(rs.getString("direction")), rs.getBigDecimal("price"), rs.getString("status"),
                    t == null ? null : t.toInstant(), rs.getTimestamp("created_at").toInstant());
        }, userId);
    }

    @Transactional
    public PriceAlertDto createAlert(UUID userId, String rawSymbol, Direction direction, BigDecimal price) {
        String symbol = MarketService.normalize(rawSymbol);
        Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM instruments WHERE symbol = ?)", Boolean.class,
                symbol);
        if (!Boolean.TRUE.equals(known)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbol", "unknown instrument");
        }
        locks.lockForTransaction("price-alerts:" + userId);
        Integer n = jdbc.queryForObject("SELECT count(*) FROM price_alerts WHERE user_id = ? AND status = 'ACTIVE'",
                Integer.class, userId);
        if (n != null && n >= MAX_ACTIVE_ALERTS) {
            throw new ApiException(ErrorCode.LIMIT_EXCEEDED, "You can have at most " + MAX_ACTIVE_ALERTS
                    + " active price alerts");
        }
        UUID id = jdbc.queryForObject("INSERT INTO price_alerts (user_id, symbol, direction, price) VALUES (?, ?, ?, ?) "
                + "RETURNING id", UUID.class, userId, symbol, direction.name(), Money.normalize(price));
        return alerts(userId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void deleteAlert(UUID userId, UUID id) {
        if (jdbc.update("DELETE FROM price_alerts WHERE id = ? AND user_id = ?", id, userId) == 0) {
            throw ApiException.notFound("Price alert");
        }
    }

    /** After a tick: fires every active alert whose price was crossed, once, with a PRICE_ALERT notification. */
    public int fireAlerts() {
        Integer fired = tx.execute(status -> {
            List<Object[]> rows = jdbc.query("""
                    UPDATE price_alerts a SET status = 'TRIGGERED', triggered_at = ?
                      FROM instruments i
                     WHERE i.symbol = a.symbol AND a.status = 'ACTIVE'
                       AND ((a.direction = 'ABOVE' AND i.price >= a.price) OR (a.direction = 'BELOW' AND i.price <= a.price))
                    RETURNING a.user_id, a.symbol, a.direction, a.price, i.price
                    """, (rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                    rs.getBigDecimal(4), rs.getBigDecimal(5)}, Timestamp.from(clock.instant()));
            for (Object[] r : rows) {
                notifications.notify((UUID) r[0], NotificationType.PRICE_ALERT, r[1] + " is " + ("ABOVE".equals(r[2])
                                ? "above" : "below") + " ₹" + Money.toJson((BigDecimal) r[3]),
                        r[1] + " is now at ₹" + Money.toJson(((BigDecimal) r[4]).setScale(2, java.math.RoundingMode.HALF_EVEN))
                                + ". This alert won't fire again.");
            }
            return rows.size();
        });
        return fired == null ? 0 : fired;
    }
}
