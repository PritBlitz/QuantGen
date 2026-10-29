package com.quantgen.trading.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Daily closes for backtests (D-046). The 14 real companies replay real NSE closes from
 * {@code market/nse_daily_2021_2026.csv} (source and licence in the {@code .SOURCE.md} next to it): a non-trading day
 * carries the last close, a day before the data (indicator warm-up only) holds the first close, and after the data
 * ends a deterministic walk continues from the last real close. The fictional QGEN keeps its simulated path from
 * 2020-01-01. Same inputs, same closes, always.
 */
final class MarketHistory {

    static final LocalDate START = LocalDate.of(2020, 1, 1);
    private static final LocalDate END = LocalDate.of(2031, 1, 1);
    private static final String CSV = "/market/nse_daily_2021_2026.csv";

    private static final Map<String, TreeMap<LocalDate, Double>> REAL = load();
    private static final Map<String, double[]> PATHS = new ConcurrentHashMap<>();

    private MarketHistory() {
    }

    static boolean isReal(String symbol) {
        return REAL.containsKey(symbol);
    }

    /** Close of {@code symbol} on {@code day} (2 dp), for 2020-01-01 to 2030-12-31. */
    static double close(String symbol, LocalDate day) {
        return PATHS.computeIfAbsent(symbol, MarketHistory::path)[(int) ChronoUnit.DAYS.between(START, day)];
    }

    private static double[] path(String symbol) {
        int days = (int) ChronoUnit.DAYS.between(START, END);
        Random r = new Random(0x5eed_0000L ^ symbol.hashCode());
        double[] p = new double[days];
        TreeMap<LocalDate, Double> real = REAL.get(symbol);
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

    /** symbol,nse_symbol,date,open,high,low,close,volume — only the QuantGen symbol, date and close are used. */
    private static Map<String, TreeMap<LocalDate, Double>> load() {
        Map<String, TreeMap<LocalDate, Double>> out = new HashMap<>();
        try (InputStream in = MarketHistory.class.getResourceAsStream(CSV)) {
            if (in == null) {
                throw new IllegalStateException(CSV + " is missing from the classpath");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.US_ASCII));
            reader.readLine(); // header
            for (String line; (line = reader.readLine()) != null; ) {
                String[] f = line.split(",");
                if (f.length != 8 || !(Double.parseDouble(f[6]) > 0)) {
                    throw new IllegalStateException("bad row in " + CSV + ": " + line);
                }
                out.computeIfAbsent(f[0], s -> new TreeMap<>()).put(LocalDate.parse(f[2]), Double.parseDouble(f[6]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Map.copyOf(out);
    }
}
