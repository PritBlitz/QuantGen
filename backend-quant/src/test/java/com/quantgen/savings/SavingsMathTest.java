package com.quantgen.savings;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Rounding rules of round-ups, FD interest and savings interest (PRD 12.5 backend notes). */
class SavingsMathTest {

    @Test
    void roundUpsGoToTheNextMultiple() {
        assertThat(RoundUpService.roundUp(new BigDecimal("123.40"), 10)).isEqualByComparingTo("6.60");
        assertThat(RoundUpService.roundUp(new BigDecimal("123.40"), 50)).isEqualByComparingTo("26.60");
        assertThat(RoundUpService.roundUp(new BigDecimal("123.40"), 100)).isEqualByComparingTo("76.60");
        assertThat(RoundUpService.roundUp(new BigDecimal("150.00"), 50)).isEqualByComparingTo("0.00");
        assertThat(RoundUpService.roundUp(new BigDecimal("0.01"), 10)).isEqualByComparingTo("9.99");
    }

    @Test
    void fdInterestIsSimpleAndHalfEvenToThePaisa() {
        assertThat(FixedDepositService.interest(new BigDecimal("100000"), new BigDecimal("7.00"), 365))
                .isEqualByComparingTo("7000.00");
        assertThat(FixedDepositService.interest(new BigDecimal("50000"), new BigDecimal("5.50"), 100))
                .isEqualByComparingTo("753.42");
        // 1000 × 3.65 × 1 / 36500 = 0.1 exactly
        assertThat(FixedDepositService.interest(new BigDecimal("1000"), new BigDecimal("3.65"), 1))
                .isEqualByComparingTo("0.10");
        // 1000 × 3.50 × 7 / 36500 = 0.671232... → 0.67
        assertThat(FixedDepositService.interest(new BigDecimal("1000"), new BigDecimal("3.50"), 7))
                .isEqualByComparingTo("0.67");
        // Half-even at the paisa: 0.125 → 0.12 (36500 × 0.125 = 4562.5 = principal × rate × days)
        assertThat(FixedDepositService.interest(new BigDecimal("4562.5"), BigDecimal.ONE, 1))
                .isEqualByComparingTo("0.12");
        assertThat(FixedDepositService.interest(new BigDecimal("1000"), new BigDecimal("7.00"), 0))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void savingsInterestPerDayKeepsFourDecimals() {
        assertThat(SavingsInterestService.dailyInterest(new BigDecimal("100000"))).isEqualByComparingTo("8.2192");
        assertThat(SavingsInterestService.dailyInterest(new BigDecimal("1"))).isEqualByComparingTo("0.0001");
    }
}
