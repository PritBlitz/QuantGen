package com.quantgen.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Money helpers. All money is INR, held as {@link BigDecimal} with 4 decimal places in storage
 * ({@code NUMERIC(19,4)}) and rounded {@link RoundingMode#HALF_EVEN}. Customer-entered amounts are
 * limited to whole paise (2 decimal places).
 */
public final class Money {

    public static final int STORAGE_SCALE = 4;
    public static final int DISPLAY_SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final String CURRENCY = "INR";

    private Money() {
    }

    /** Normalizes a value to the storage scale; fails if that would lose precision. */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount.setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
    }

    /** Rounds a computed value (e.g. interest) to whole paise, half-even. */
    public static BigDecimal roundToPaise(BigDecimal amount) {
        return amount.setScale(DISPLAY_SCALE, ROUNDING).setScale(STORAGE_SCALE, RoundingMode.UNNECESSARY);
    }

    public static boolean isPositive(BigDecimal amount) {
        return amount != null && amount.signum() > 0;
    }

    /**
     * JSON form: a plain string without exponent, with at least 2 decimal places and never fewer than
     * the value actually has (so formatting is lossless). E.g. 1250.5000 → "1250.50".
     */
    public static String toJson(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        int scale = Math.max(DISPLAY_SCALE, stripped.scale());
        return value.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }
}
