package com.quantgen.trading.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Daily closes for backtests (D-046, D-047). The 14 real companies replay real NSE closes: the vendored
 * {@code market/nse_daily_2021_2026.csv} (source and licence in the {@code .SOURCE.md} next to it) plus the days
 * {@link MarketDataSync} has fetched since, back-adjusted by recorded corporate-action factors so a split never
 * looks like a crash. A non-trading day carries the last close, a day before the data (indicator warm-up only)
 * holds the first close, and after the data ends a deterministic walk continues from the last real close. The
 * fictional QGEN keeps its simulated path from 2020-01-01. Same data, same closes.
 */
@Component
public class MarketHistory {

    static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final LocalDate END = LocalDate.of(2031, 1, 1);
    private static final String CSV = "/market/nse_daily_2021_2026.csv";

    /** QuantGen symbol → NSE symbol, and the vendored raw closes, from the classpath file. */
    static final Map<String, String> NSE_SYMBOLS;
    private static final Map<String, TreeMap<LocalDate, Double>> VENDORED;

    static {
        Map<String, String> nse = new HashMap<>();
        Map<String, TreeMap<LocalDate, Double>> closes = new HashMap<>();
        load(nse, closes);
        NSE_SYMBOLS = Map.copyOf(nse);
        VENDORED = Map.copyOf(closes);
    }

    private record Snapshot(Map<String, TreeMap<LocalDate, Double>> real, Map<String, double[]> paths) {
    }

    private final JdbcTemplate jdbc;
    private volatile Snapshot snapshot;

    @Autowired
    public MarketHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The vendored file only, no database (unit tests). */
    static MarketHistory vendoredOnly() {
        return new MarketHistory(null);
    }

    static boolean isReal(String symbol) {
        return NSE_SYMBOLS.containsKey(symbol);
    }

    /** The last day in the vendored file for {@code symbol}, and its raw close. */
    static Map.Entry<LocalDate, Double> lastVendored(String symbol) {
        return VENDORED.get(symbol).lastEntry();
    }

    /** Close of {@code symbol} on {@code day} (2 dp), for 2020-01-01 to 2030-12-31. */
    public double close(String symbol, LocalDate day) {
        Snapshot s = snapshot();
        return s.paths().computeIfAbsent(symbol, sym -> path(sym, s.real().get(sym)))
                [(int) ChronoUnit.DAYS.between(START, day)];
    }

    /** Picks up newly fetched days and adjustments on the next read. */
    public void reload() {
        snapshot = null;
    }

    private Snapshot snapshot() {
        Snapshot s = snapshot;
        if (s == null) {
            s = new Snapshot(real(), new ConcurrentHashMap<>());
            snapshot = s;
        }
        return s;
    }

    /** Vendored + fetched raw closes, each multiplied by every factor that takes effect after its day. */
    private Map<String, TreeMap<LocalDate, Double>> real() {
        Map<String, TreeMap<LocalDate, Double>> raw = new HashMap<>();
        VENDORED.forEach((sym, closes) -> raw.put(sym, new TreeMap<>(closes)));
        Map<String, TreeMap<LocalDate, BigDecimal>> factors = new HashMap<>();
        if (jdbc != null) {
            jdbc.query("SELECT symbol, day, close FROM market_daily_closes", rs -> {
                TreeMap<LocalDate, Double> closes = raw.get(rs.getString(1));
                if (closes != null) {
                    closes.put(rs.getObject(2, LocalDate.class), rs.getBigDecimal(3).doubleValue());
                }
            });
            jdbc.query("SELECT symbol, effective_date, factor FROM market_adjustments", rs -> {
                factors.computeIfAbsent(rs.getString(1), k -> new TreeMap<>())
                        .put(rs.getObject(2, LocalDate.class), rs.getBigDecimal(3));
            });
        }
        Map<String, TreeMap<LocalDate, Double>> adjusted = new HashMap<>();
        raw.forEach((sym, closes) -> {
            NavigableMap<LocalDate, BigDecimal> f = factors.getOrDefault(sym, new TreeMap<>());
            TreeMap<LocalDate, Double> out = new TreeMap<>();
            for (Map.Entry<LocalDate, Double> e : closes.entrySet()) {
                double product = 1.0;
                for (BigDecimal factor : f.tailMap(e.getKey(), false).values()) { // factors dated after this day
                    product *= factor.doubleValue();
                }
                out.put(e.getKey(), Math.round(e.getValue() * product * 100) / 100.0);
            }
            adjusted.put(sym, out);
        });
        return Map.copyOf(adjusted);
    }

    private static double[] path(String symbol, TreeMap<LocalDate, Double> real) {
        int days = (int) ChronoUnit.DAYS.between(START, END);
        Random r = new Random(0x5eed_0000L ^ symbol.hashCode());
        double[] p = new double[days];
        if (real == null) {
            p[0] = 100 + Math.floorMod(symbol.hashCode(), 2900);
            walk(p, 1, r);
            return p;
        }
        LocalDate last = real.lastKey();
        int i = 0;
        for (LocalDate d = START; !d.isAfter(last); d = d.plusDays(1), i++) {
            Map.Entry<LocalDate, Double> e = real.floorEntry(d);
            p[i] = e == null ? real.firstEntry().getValue() : e.getValue();
        }
        walk(p, i, r);
        return p;
    }

    private static void walk(double[] p, int from, Random r) {
        for (int i = from; i < p.length; i++) {
            p[i] = Math.max(1.0, p[i - 1] * Math.exp(0.0003 + 0.018 * r.nextGaussian()));
            p[i] = Math.round(p[i] * 100) / 100.0;
        }
    }

    /** symbol,nse_symbol,date,open,high,low,close,volume — the QuantGen and NSE symbols, date and close are used. */
    private static void load(Map<String, String> nse, Map<String, TreeMap<LocalDate, Double>> closes) {
        try (InputStream in = MarketHistory.class.getResourceAsStream(CSV)) {
            if (in == null) {
                throw new IllegalStateException(CSV + " is missing from the classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            reader.readLine(); // header
            for (String line; (line = reader.readLine()) != null; ) {
                List<String> f = List.of(line.split(","));
                if (f.size() != 8 || !(Double.parseDouble(f.get(6)) > 0)) {
                    throw new IllegalStateException("bad row in " + CSV + ": " + line);
                }
                nse.put(f.get(0), f.get(1));
                closes.computeIfAbsent(f.get(0), s -> new TreeMap<>()).put(LocalDate.parse(f.get(2)),
                        Double.parseDouble(f.get(6)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
