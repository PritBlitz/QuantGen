package com.quantgen.trading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.quantgen.trading.domain.Holding;
import com.quantgen.trading.service.PriceWalk;

class TradingUnitTest {

    @Test
    void priceStepIsBoundedRoundedAndNeverAtOrBelowOne() {
        BigDecimal open = new BigDecimal("100.00");
        // Extreme noise is capped at ±1%.
        assertThat(PriceWalk.next(new BigDecimal("100.00"), open, 50)).isEqualByComparingTo("101.00");
        assertThat(PriceWalk.next(new BigDecimal("100.00"), open, -50)).isEqualByComparingTo("99.00");
        assertThat(PriceWalk.next(new BigDecimal("100.00"), open, 0.3).scale()).isEqualTo(2);
        // Floor.
        assertThat(PriceWalk.next(new BigDecimal("1.01"), new BigDecimal("1.01"), -50)).isEqualByComparingTo("1.01");

        // A long random walk stays inside sane bounds and above the floor.
        Random r = new Random(42);
        BigDecimal p = open;
        for (int i = 0; i < 100_000; i++) {
            BigDecimal next = PriceWalk.next(p, open, r.nextGaussian());
            BigDecimal change = next.subtract(p).abs();
            assertThat(change).isLessThanOrEqualTo(p.multiply(new BigDecimal("0.0101")));
            assertThat(next).isGreaterThan(BigDecimal.ONE);
            p = next;
        }
    }

    @Test
    void meanReversionPullsTowardsOpen() {
        BigDecimal open = new BigDecimal("100.00");
        assertThat(PriceWalk.next(new BigDecimal("150.00"), open, 0)).isLessThan(new BigDecimal("150.00"));
        assertThat(PriceWalk.next(new BigDecimal("50.00"), open, 0)).isGreaterThan(new BigDecimal("50.00"));
    }

    @Test
    void holdingPnlMath() {
        Holding h = newHolding();
        h.buy(10, new BigDecimal("1000.00"));   // 10 @ 100
        h.buy(10, new BigDecimal("1100.00"));   // 10 @ 110 → avg 105
        assertThat(h.averageCost()).isEqualByComparingTo("105");

        assertThat(h.sell(5, new BigDecimal("600.00"))).isEqualByComparingTo("75");   // 5 @ 120
        assertThat(h.getQuantity()).isEqualTo(15);
        assertThat(h.getCostBasis()).isEqualByComparingTo("1575");
        assertThat(h.averageCost()).isEqualByComparingTo("105");

        assertThat(h.sell(15, new BigDecimal("1350.00"))).isEqualByComparingTo("-225"); // 15 @ 90
        assertThat(h.getQuantity()).isZero();
        assertThat(h.getCostBasis()).isEqualByComparingTo("0");
        // Total realized = sells (1950) − buys (2100).
        assertThat(h.getRealizedPnl()).isEqualByComparingTo("-150");
    }

    @Test
    void sellingTheWholePositionReleasesTheExactBasisDespiteRounding() {
        Holding h = newHolding();
        h.buy(3, new BigDecimal("100.00"));      // avg 33.3333…
        h.sell(1, new BigDecimal("40.00"));      // releases 33.3333
        h.sell(1, new BigDecimal("40.00"));      // releases 33.3334 (half-even on 66.6667/2)
        h.sell(1, new BigDecimal("40.00"));      // releases whatever is left
        assertThat(h.getCostBasis()).isEqualByComparingTo("0");
        assertThat(h.getRealizedPnl()).isEqualByComparingTo("20.00"); // 120 − 100, exactly
    }

    @Test
    void cannotSellMoreThanHeld() {
        Holding h = newHolding();
        h.buy(2, new BigDecimal("20"));
        assertThatThrownBy(() -> h.sell(3, new BigDecimal("30"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static Holding newHolding() {
        Holding h = org.springframework.beans.BeanUtils.instantiateClass(Holding.class);
        ReflectionTestUtils.setField(h, "costBasis", BigDecimal.ZERO);
        ReflectionTestUtils.setField(h, "realizedPnl", BigDecimal.ZERO);
        return h;
    }
}
