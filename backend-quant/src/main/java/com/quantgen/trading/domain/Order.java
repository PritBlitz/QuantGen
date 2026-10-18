package com.quantgen.trading.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.CreationTimestamp;

import com.quantgen.common.money.Money;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** An order (F-TRD-3, F-TRD-7): a filled market order, or a limit/stop order that is OPEN until it fills or ends. */
@Entity(name = "TradeOrder") // "Order" is a JPQL keyword
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, length = 10)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderSide side;

    @Column(nullable = false, updatable = false)
    private long quantity;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal total;

    @Column(name = "realized_pnl", updatable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OrderStatus status;

    @Column(name = "journal_entry_id", updatable = false)
    private UUID journalEntryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, updatable = false)
    private OrderType orderType = OrderType.MARKET;

    @Column(name = "limit_price", updatable = false, precision = 19, scale = 4)
    private BigDecimal limitPrice;

    @Column(name = "trigger_price", updatable = false, precision = 19, scale = 4)
    private BigDecimal triggerPrice;

    @Enumerated(EnumType.STRING)
    @Column(updatable = false, length = 3)
    private OrderValidity validity;

    @Column(name = "expires_at", updatable = false)
    private Instant expiresAt;

    /** Set on fill. Later changes to OPEN orders (fill, cancel, expiry) are made with SQL by OrderBook. */
    @Column(name = "filled_at", updatable = false)
    private Instant filledAt;

    @Column(name = "hold_id", updatable = false)
    private UUID holdId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static Order filled(UUID userId, UUID accountId, String symbol, OrderSide side, long quantity,
                               BigDecimal price, BigDecimal total, BigDecimal realizedPnl, UUID journalEntryId) {
        Order o = new Order();
        o.userId = userId;
        o.accountId = accountId;
        o.symbol = symbol;
        o.side = side;
        o.quantity = quantity;
        o.price = price;
        o.total = total;
        o.realizedPnl = realizedPnl;
        o.status = OrderStatus.FILLED;
        o.journalEntryId = journalEntryId;
        o.filledAt = Instant.now();
        return o;
    }

    /** Records the terms of a limit/stop order that filled on placement. Only before it is saved. */
    public void setTerms(OrderType type, BigDecimal limitPrice, BigDecimal triggerPrice, OrderValidity validity) {
        this.orderType = type;
        this.limitPrice = limitPrice == null ? null : Money.normalize(limitPrice);
        this.triggerPrice = triggerPrice == null ? null : Money.normalize(triggerPrice);
        this.validity = validity;
    }

    /** A limit or stop order waiting for its price; {@code price} is the limit/trigger and {@code total} the estimate. */
    public static Order open(UUID userId, UUID accountId, String symbol, OrderSide side, long quantity, OrderType type,
                             BigDecimal limitPrice, BigDecimal triggerPrice, OrderValidity validity, Instant expiresAt,
                             UUID holdId) {
        Order o = new Order();
        o.userId = userId;
        o.accountId = accountId;
        o.symbol = symbol;
        o.side = side;
        o.quantity = quantity;
        o.orderType = type;
        o.limitPrice = limitPrice;
        o.triggerPrice = triggerPrice;
        o.price = type == OrderType.LIMIT ? limitPrice : triggerPrice;
        o.total = Money.normalize(o.price.multiply(BigDecimal.valueOf(quantity)));
        o.status = OrderStatus.OPEN;
        o.validity = validity;
        o.expiresAt = expiresAt;
        o.holdId = holdId;
        return o;
    }
}
