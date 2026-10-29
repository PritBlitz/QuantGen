package com.quantgen.trading.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Backtest closes: real NSE history for the 14 real symbols, a deterministic walk outside it. */
class MarketHistoryTest {

    @Test
    void realSymbolsReplayRealNseCloses() {
        assertThat(MarketHistory.close("INFY", LocalDate.of(2021, 1, 1))).isEqualTo(1260.45);
        assertThat(MarketHistory.close("WIPR", LocalDate.of(2026, 1, 22))).isEqualTo(240.65);
        assertThat(MarketHistory.close("TATM", LocalDate.of(2025, 10, 14))).isEqualTo(395.45); // the demerger day
        // A weekend carries Friday's close; before the data starts (indicator warm-up) the first close holds.
        assertThat(MarketHistory.close("WIPR", LocalDate.of(2026, 1, 17)))
                .isEqualTo(MarketHistory.close("WIPR", LocalDate.of(2026, 1, 16)));
        assertThat(MarketHistory.close("INFY", LocalDate.of(2020, 9, 1))).isEqualTo(1260.45);
    }

    @Test
    void afterTheDataEndsTheWalkContinuesFromTheLastRealClose() {
        double last = MarketHistory.close("WIPR", LocalDate.of(2026, 1, 22));
        double next = MarketHistory.close("WIPR", LocalDate.of(2026, 1, 23));
        assertThat(next).isCloseTo(last, within(last * 0.10)); // continuous, no jump to a synthetic level
        assertThat(MarketHistory.close("WIPR", LocalDate.of(2026, 10, 29)))
                .isEqualTo(MarketHistory.close("WIPR", LocalDate.of(2026, 10, 29))); // deterministic
    }

    @Test
    void theFictionalSymbolStaysSimulated() {
        assertThat(MarketHistory.isReal("QGEN")).isFalse();
        assertThat(MarketHistory.isReal("INFY")).isTrue();
        assertThat(MarketHistory.close("QGEN", LocalDate.of(2024, 6, 3))).isPositive();
    }
}
