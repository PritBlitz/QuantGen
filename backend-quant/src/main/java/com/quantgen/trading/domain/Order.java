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

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A filled market order (F-TRD-3). Orders are immutable once filled. */
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
        return o;
    }
}
