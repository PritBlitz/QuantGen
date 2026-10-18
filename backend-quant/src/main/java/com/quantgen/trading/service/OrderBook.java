package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;
import com.quantgen.common.metrics.QgMetrics;
import com.quantgen.common.money.Money;
import com.quantgen.ledger.domain.JournalType;
import com.quantgen.ledger.service.JournalRequest;
import com.quantgen.ledger.service.LedgerService;
import com.quantgen.ledger.service.SystemAccount;
import com.quantgen.trading.api.TradingDtos.OrderDto;
import com.quantgen.trading.domain.Holding;
import com.quantgen.trading.domain.HoldingRepository;
import com.quantgen.trading.domain.Instrument;
import com.quantgen.trading.domain.InstrumentRepository;
import com.quantgen.trading.domain.OrderSide;
import com.quantgen.trading.domain.OrderStatus;
import com.quantgen.trading.domain.OrderType;
import com.quantgen.trading.domain.OrderValidity;

/**
 * F-TRD-7 open orders. After every price tick, each OPEN order whose price is reached is filled in its own
 * transaction: a LIMIT at its limit price, a STOP_LOSS at the tick price. Open orders can be cancelled and expire
 * (DAY after 24 hours, GTC after 90 days); ending an order releases its cash hold or reserved shares. Lock order:
 * order row → holding row → instrument (FOR SHARE) → ledger rows, so a fill and a cancel of the same order are
 * serialized and only one of them happens.
 */
@Service
public class OrderBook {

    private static final Logger log = LoggerFactory.getLogger(OrderBook.class);

    public static final Duration DAY = Duration.ofHours(24);
    public static final Duration GTC = Duration.ofDays(90);

    private record Open(UUID id, UUID userId, UUID accountId, String symbol, OrderSide side, long quantity,
                        OrderType type, BigDecimal limitPrice, BigDecimal triggerPrice, UUID holdId, OrderStatus status,
                        Instant expiresAt) {
    }

    private final JdbcTemplate jdbc;
    private final HoldingRepository holdings;
    private final InstrumentRepository instruments;
    private final LedgerService ledger;
    private final QgMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderBook(JdbcTemplate jdbc, HoldingRepository holdings, InstrumentRepository instruments,
                     LedgerService ledger, QgMetrics metrics, Clock clock, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.holdings = holdings;
        this.instruments = instruments;
        this.ledger = ledger;
        this.metrics = metrics;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Whether an order of this type would fill at {@code price}. MARKET always does. */
    public static boolean reached(OrderType type, OrderSide side, BigDecimal price, BigDecimal limit,
                                  BigDecimal trigger) {
        return switch (type) {
            case MARKET -> true;
            case LIMIT -> side == OrderSide.BUY ? price.compareTo(limit) <= 0 : price.compareTo(limit) >= 0;
            case STOP_LOSS -> price.compareTo(trigger) <= 0;
        };
    }

    // ------------------------------------------------------------------ matching

    /** Fills every OPEN order whose price is reached at the current prices; returns how many filled. */
    public int matchAll() {
        List<UUID> candidates = jdbc.queryForList("""
                SELECT o.id FROM orders o JOIN instruments i ON i.symbol = o.symbol
                 WHERE o.status = 'OPEN' AND o.expires_at > ?
                   AND ((o.order_type = 'LIMIT' AND o.side = 'BUY' AND i.price <= o.limit_price)
                     OR (o.order_type = 'LIMIT' AND o.side = 'SELL' AND i.price >= o.limit_price)
                     OR (o.order_type = 'STOP_LOSS' AND i.price <= o.trigger_price))
                 ORDER BY o.created_at, o.id
                """, UUID.class, Timestamp.from(clock.instant()));
        int filled = 0;
        for (UUID id : candidates) {
            try {
                if (Boolean.TRUE.equals(tx.execute(status -> fillOne(id)))) {
                    filled++;
                }
            } catch (RuntimeException e) {
                // E.g. the account was frozen since: the order stays OPEN and is tried again on the next tick.
                log.warn("Open order {} could not fill: {}", id, e.toString());
            }
        }
        return filled;
    }

    private boolean fillOne(UUID id) {
        Open o = lock(id, true).orElse(null);
        if (o == null || o.status() != OrderStatus.OPEN || !o.expiresAt().isAfter(clock.instant())) {
            return false;
        }
        holdings.ensureExists(o.userId(), o.symbol());
        Holding holding = holdings.lockByUserIdAndSymbol(o.userId(), o.symbol()).orElseThrow();
        Instrument instrument = instruments.findBySymbolForShare(o.symbol()).orElseThrow();
        BigDecimal market = instrument.getPrice();
        if (!reached(o.type(), o.side(), market, o.limitPrice(), o.triggerPrice())) {
            return false; // the price moved back before we got the lock
        }
        BigDecimal price = o.type() == OrderType.LIMIT ? o.limitPrice() : market;
        BigDecimal total = Money.normalize(price.multiply(BigDecimal.valueOf(o.quantity())));
        String description = o.side() + " " + o.quantity() + " " + o.symbol() + " @ " + Money.toJson(price)
                + (o.type() == OrderType.LIMIT ? " (limit)" : " (stop-loss)");
        JournalRequest.Builder journal = JournalRequest.builder(JournalType.TRADE, description, o.userId());
        BigDecimal realized = null;
        if (o.side() == OrderSide.BUY) {
            releaseHoldIfActive(o.holdId()); // the debit below spends exactly what the hold kept aside
            journal.debit(o.accountId(), total, o.symbol()).credit(SystemAccount.BROKERAGE_CLEARING.id(), total, null);
            holding.buy(o.quantity(), total);
        } else {
            holding.unreserve(o.quantity());
            realized = holding.sell(o.quantity(), total);
            journal.debit(SystemAccount.BROKERAGE_CLEARING.id(), total, null).credit(o.accountId(), total, o.symbol());
        }
        UUID entry = ledger.post(journal.build()).journalEntryId();
        holdings.flush();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                UPDATE orders SET status = 'FILLED', price = ?, total = ?, realized_pnl = ?, journal_entry_id = ?,
                                  filled_at = ?, closed_at = ? WHERE id = ?
                """, price, total, realized, entry, now, now, id);
        metrics.orderFilled(o.side().name());
        return true;
    }

    // ------------------------------------------------------------------ cancel and expiry

    @Transactional
    public OrderDto cancel(UUID userId, UUID id) {
        Open o = lock(id, false).filter(x -> x.userId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (o.status() != OrderStatus.OPEN) {
            throw new ApiException(ErrorCode.INVALID_STATE, "Only an open order can be cancelled");
        }
        end(o, OrderStatus.CANCELLED);
        return dto(id);
    }

    @Scheduled(fixedDelayString = "${qg.orders.expiry-millis:60000}")
    void scheduledExpiry() {
        try {
            expireDue();
        } catch (RuntimeException e) {
            log.error("Order expiry failed", e);
        }
    }

    /** Ends every OPEN order past its expiry; returns how many. */
    public int expireDue() {
        List<UUID> due = jdbc.queryForList("SELECT id FROM orders WHERE status = 'OPEN' AND expires_at <= ?",
                UUID.class, Timestamp.from(clock.instant()));
        int n = 0;
        for (UUID id : due) {
            Boolean done = tx.execute(status -> {
                Open o = lock(id, true).orElse(null);
                if (o == null || o.status() != OrderStatus.OPEN) {
                    return false;
                }
                end(o, OrderStatus.EXPIRED);
                return true;
            });
            if (Boolean.TRUE.equals(done)) {
                n++;
            }
        }
        return n;
    }

    private void end(Open o, OrderStatus status) {
        if (o.side() == OrderSide.BUY) {
            releaseHoldIfActive(o.holdId());
        } else {
            holdings.lockByUserIdAndSymbol(o.userId(), o.symbol()).orElseThrow().unreserve(o.quantity());
            holdings.flush();
        }
        jdbc.update("UPDATE orders SET status = ?, closed_at = ? WHERE id = ?", status.name(),
                Timestamp.from(clock.instant()), o.id());
    }

    private void releaseHoldIfActive(UUID holdId) {
        String status = jdbc.queryForObject("SELECT status FROM holds WHERE id = ?", String.class, holdId);
        if ("ACTIVE".equals(status)) {
            ledger.releaseHold(holdId);
        }
    }

    // ------------------------------------------------------------------ rows

    private Optional<Open> lock(UUID id, boolean skipLocked) {
        return jdbc.query("SELECT * FROM orders WHERE id = ? FOR UPDATE" + (skipLocked ? " SKIP LOCKED" : ""),
                (rs, i) -> new Open(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                        rs.getObject("account_id", UUID.class), rs.getString("symbol"),
                        OrderSide.valueOf(rs.getString("side")), rs.getLong("quantity"),
                        OrderType.valueOf(rs.getString("order_type")), rs.getBigDecimal("limit_price"),
                        rs.getBigDecimal("trigger_price"), rs.getObject("hold_id", UUID.class),
                        OrderStatus.valueOf(rs.getString("status")), instant(rs, "expires_at")), id)
                .stream().findFirst();
    }

    /** An order read with SQL (after SQL updates, so never a stale JPA copy). */
    OrderDto dto(UUID id) {
        return jdbc.queryForObject("SELECT * FROM orders WHERE id = ?", (rs, i) -> new OrderDto(
                rs.getObject("id", UUID.class), rs.getString("symbol"), OrderSide.valueOf(rs.getString("side")),
                rs.getLong("quantity"), rs.getBigDecimal("price"), rs.getBigDecimal("total"),
                OrderStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
                OrderType.valueOf(rs.getString("order_type")), rs.getBigDecimal("limit_price"),
                rs.getBigDecimal("trigger_price"),
                rs.getString("validity") == null ? null : OrderValidity.valueOf(rs.getString("validity")),
                instant(rs, "filled_at")), id);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
