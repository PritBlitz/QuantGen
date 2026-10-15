package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One step of the bounded random walk (F-TRD-2): a small pull back towards the opening price plus Gaussian
 * noise, with each step capped at ±1% and the price never at or below ₹1.
 */
public final class PriceWalk {

    static final double VOLATILITY = 0.0015;      // σ per 3-second tick
    static final double MEAN_REVERSION = 0.002;   // pull towards the opening price
    static final double MAX_STEP = 0.01;          // |change| ≤ 1% per tick
    static final BigDecimal FLOOR = new BigDecimal("1.01");

    private PriceWalk() {
    }

    /** @param gaussian a standard-normal sample */
    public static BigDecimal next(BigDecimal price, BigDecimal openPrice, double gaussian) {
        double p = price.doubleValue();
        double drift = MEAN_REVERSION * (openPrice.doubleValue() - p) / p;
        double step = Math.clamp(drift + VOLATILITY * gaussian, -MAX_STEP, MAX_STEP);
        BigDecimal next = price.multiply(BigDecimal.valueOf(1 + step)).setScale(2, RoundingMode.HALF_EVEN);
        return next.compareTo(FLOOR) < 0 ? FLOOR.setScale(2) : next;
    }
}
