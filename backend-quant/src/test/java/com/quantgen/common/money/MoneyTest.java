package com.quantgen.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void jsonFormIsPlainWithAtLeastTwoDecimals() {
        assertThat(Money.toJson(new BigDecimal("1250.5000"))).isEqualTo("1250.50");
        assertThat(Money.toJson(new BigDecimal("0"))).isEqualTo("0.00");
        assertThat(Money.toJson(new BigDecimal("1E+7"))).isEqualTo("10000000.00");
        assertThat(Money.toJson(new BigDecimal("-3.5"))).isEqualTo("-3.50");
    }

    @Test
    void jsonFormNeverDropsPrecision() {
        assertThat(Money.toJson(new BigDecimal("12.3456"))).isEqualTo("12.3456");
        assertThat(Money.toJson(new BigDecimal("12.3450"))).isEqualTo("12.345");
    }

    @Test
    void roundToPaiseIsHalfEven() {
        assertThat(Money.roundToPaise(new BigDecimal("1.005"))).isEqualByComparingTo("1.00");
        assertThat(Money.roundToPaise(new BigDecimal("1.015"))).isEqualByComparingTo("1.02");
        assertThat(Money.roundToPaise(new BigDecimal("1.005")).scale()).isEqualTo(Money.STORAGE_SCALE);
    }

    @Test
    void normalizeRefusesToLosePrecision() {
        assertThat(Money.normalize(new BigDecimal("10.5"))).isEqualTo(new BigDecimal("10.5000"));
        assertThatThrownBy(() -> Money.normalize(new BigDecimal("10.00001"))).isInstanceOf(ArithmeticException.class);
    }
}
