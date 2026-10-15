package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.error.ApiException;
import com.quantgen.trading.api.TradingDtos.InstrumentDto;
import com.quantgen.trading.api.TradingDtos.PricePointDto;
import com.quantgen.trading.domain.Instrument;
import com.quantgen.trading.domain.InstrumentRepository;

/** Read side of the simulated market: instruments, 24h change and price history (F-TRD-1/6). */
@Service
public class MarketService {

    /** History ranges (F-TRD-6) and the bucket each one is downsampled to. */
    public enum Range {
        H1("1H", Duration.ofHours(1), "15 seconds"),
        D1("1D", Duration.ofDays(1), "5 minutes"),
        W1("1W", Duration.ofDays(7), "30 minutes");

        final String label;
        final Duration window;
        final String bucket;

        Range(String label, Duration window, String bucket) {
            this.label = label;
            this.window = window;
            this.bucket = bucket;
        }

        public static Range parse(String value) {
            for (Range r : values()) {
                if (r.label.equalsIgnoreCase(value)) {
                    return r;
                }
            }
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "range", "must be one of 1H, 1D, 1W");
        }
    }

    private final InstrumentRepository instruments;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public MarketService(InstrumentRepository instruments, JdbcTemplate jdbc, Clock clock) {
        this.instruments = instruments;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<InstrumentDto> list() {
        Map<String, BigDecimal> reference = referencePrices();
        return instruments.findAllByOrderBySymbolAsc().stream().map(i -> toDto(i, reference)).toList();
    }

    @Transactional(readOnly = true)
    public InstrumentDto get(String symbol) {
        Instrument instrument = find(symbol);
        return toDto(instrument, referencePrices());
    }

    @Transactional(readOnly = true)
    public List<PricePointDto> history(String symbol, Range range) {
        Instrument instrument = find(symbol);
        Instant since = clock.instant().minus(range.window);
        return jdbc.query("""
                SELECT max(ts) AS t, (array_agg(price ORDER BY ts DESC))[1] AS price
                  FROM price_ticks
                 WHERE symbol = ? AND ts >= ?
                 GROUP BY date_bin(CAST(? AS interval), ts, TIMESTAMPTZ '2000-01-01 00:00:00+00')
                 ORDER BY t
                """, (rs, i) -> new PricePointDto(rs.getTimestamp("t").toInstant(), rs.getBigDecimal("price")),
                instrument.getSymbol(), Timestamp.from(since), range.bucket);
    }

    /** Is the symbol a listed instrument? Case-insensitive. */
    @Transactional(readOnly = true)
    public Instrument find(String symbol) {
        return instruments.findBySymbol(normalize(symbol)).orElseThrow(() -> ApiException.notFound("Instrument"));
    }

    static String normalize(String symbol) {
        return symbol == null ? "" : symbol.trim().toUpperCase(java.util.Locale.ROOT);
    }

    /** The price ~24h ago per symbol: the oldest tick inside the window, else the opening price. */
    private Map<String, BigDecimal> referencePrices() {
        Instant since = clock.instant().minus(Duration.ofDays(1));
        Map<String, BigDecimal> refs = new HashMap<>();
        jdbc.query("""
                SELECT i.symbol,
                       COALESCE((SELECT p.price FROM price_ticks p
                                  WHERE p.symbol = i.symbol AND p.ts >= ?
                                  ORDER BY p.ts ASC LIMIT 1), i.open_price) AS ref
                  FROM instruments i
                """, rs -> {
            refs.put(rs.getString("symbol"), rs.getBigDecimal("ref"));
        }, Timestamp.from(since));
        return refs;
    }

    private static InstrumentDto toDto(Instrument i, Map<String, BigDecimal> reference) {
        BigDecimal ref = reference.getOrDefault(i.getSymbol(), i.getOpenPrice());
        BigDecimal change = ref.signum() == 0
                ? BigDecimal.ZERO
                : i.getPrice().subtract(ref).multiply(BigDecimal.valueOf(100)).divide(ref, 2, RoundingMode.HALF_EVEN);
        return new InstrumentDto(i.getSymbol(), i.getName(), i.getSector(), i.getPrice(), change, i.getUpdatedAt());
    }
}
