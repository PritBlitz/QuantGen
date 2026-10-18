package com.quantgen.trading.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.quantgen.common.money.Money;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A user's position in one instrument. {@code costBasis} is the exact remaining cost of the position
 * (so average cost never drifts through rounding); {@code realizedPnl} accumulates over all sells.
 */
@Entity
@Table(name = "holdings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Holding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, updatable = false, length = 10)
    private String symbol;

    @Column(nullable = false)
    private long quantity;

    /** Shares promised to open SELL orders (V2-M7); they can't be sold or reserved again. */
    @Column(name = "reserved_qty", nullable = false)
    private long reservedQty;

    @Column(name = "cost_basis", nullable = false, precision = 19, scale = 4)
    private BigDecimal costBasis;

    @Column(name = "realized_pnl", nullable = false, precision = 19, scale = 4)
    private BigDecimal realizedPnl;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    /** Adds bought shares at their total cost. */
    public void buy(long qty, BigDecimal total) {
        if (qty <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        quantity += qty;
        costBasis = costBasis.add(total);
    }

    /**
     * Removes sold shares, releasing a proportional share of the cost basis, and returns the realized
     * P&L {@code proceeds − releasedCost}. Selling the whole position releases the whole basis exactly.
     */
    public BigDecimal sell(long qty, BigDecimal proceeds) {
        if (qty <= 0 || qty > quantity) {
            throw new IllegalArgumentException("invalid sell quantity");
        }
        BigDecimal released = qty == quantity
                ? costBasis
                : costBasis.multiply(BigDecimal.valueOf(qty))
                        .divide(BigDecimal.valueOf(quantity), Money.STORAGE_SCALE, RoundingMode.HALF_EVEN);
        quantity -= qty;
        costBasis = costBasis.subtract(released);
        BigDecimal realized = proceeds.subtract(released);
        realizedPnl = realizedPnl.add(realized);
        return realized;
    }

    /** Shares free to sell or reserve. */
    public long available() {
        return quantity - reservedQty;
    }

    public void reserve(long qty) {
        if (qty <= 0 || qty > available()) {
            throw new IllegalArgumentException("invalid reserve quantity");
        }
        reservedQty += qty;
    }

    public void unreserve(long qty) {
        if (qty <= 0 || qty > reservedQty) {
            throw new IllegalArgumentException("invalid unreserve quantity");
        }
        reservedQty -= qty;
    }

    /** Average cost per share (4 dp); zero for an empty position. */
    public BigDecimal averageCost() {
        return quantity == 0
                ? BigDecimal.ZERO.setScale(Money.STORAGE_SCALE)
                : costBasis.divide(BigDecimal.valueOf(quantity), Money.STORAGE_SCALE, RoundingMode.HALF_EVEN);
    }
}
