package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.money.Money;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * F-TRD-10 strategy backtester over daily closes from {@link MarketHistory} (D-046: real NSE closes for the real
 * companies, a deterministic walk outside the data and for the fictional QGEN), so the same request always gives the
 * same result. Whole shares, no fees; SMA_CROSS / RSI / BUY_THE_DIP split the cash equally per
 * symbol and go all in or all out; REBALANCE resets the portfolio to its weights every {@code everyDays} days.
 */
@Service
public class BacktestService {

    static final LocalDate EARLIEST_FROM = LocalDate.of(2021, 1, 1);
    static final int MAX_DAYS = 1826;
    static final int MAX_POINTS = 400;
    static final int KEEP = 20;

    public record BacktestRequest(JsonNode strategy, List<String> symbols, LocalDate from, LocalDate to,
                                  BigDecimal initialCash) {
    }

    public record EquityPoint(LocalDate t, BigDecimal value) {
    }

    public record Trade(LocalDate t, String symbol, String side, long quantity, BigDecimal price) {
    }

    public record BacktestDto(UUID id, JsonNode strategy, List<String> symbols, LocalDate from, LocalDate to,
                              BigDecimal initialCash, BigDecimal finalValue, BigDecimal returnPct,
                              BigDecimal maxDrawdownPct, BigDecimal winRatePct, List<EquityPoint> equity,
                              List<Trade> trades, java.time.Instant createdAt) {
    }

    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final Clock clock;

    public BacktestService(JdbcTemplate jdbc, JsonMapper json, Clock clock) {
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ history

    /** Daily close of {@code symbol} on {@code day} (deterministic, 2 dp). */
    double close(String symbol, LocalDate day) {
        return MarketHistory.close(symbol, day);
    }

    // ------------------------------------------------------------------ API

    @Transactional
    public BacktestDto run(UUID userId, BacktestRequest req) {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        List<String> symbols = symbols(req.symbols());
        if (req.from() == null || req.to() == null) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, req.from() == null ? "from" : "to", "is required");
        }
        if (req.from().isBefore(EARLIEST_FROM)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must be on or after " + EARLIEST_FROM);
        }
        if (req.to().isAfter(today) || !req.to().isAfter(req.from().plusDays(29))) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "to", "must be at least 30 days after 'from' and not in the future");
        }
        if (ChronoUnit.DAYS.between(req.from(), req.to()) > MAX_DAYS) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "to", "the range can be at most 5 years");
        }
        BigDecimal cash0 = req.initialCash();
        if (cash0 == null || cash0.compareTo(new BigDecimal("1000")) < 0 || cash0.compareTo(new BigDecimal("10000000")) > 0
                || cash0.stripTrailingZeros().scale() > 2) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "initialCash", "must be 1000.00-10000000.00");
        }
        Strategy s = Strategy.parse(req.strategy(), symbols);
        Result result = simulate(s, symbols, req.from(), req.to(), cash0.doubleValue());

        UUID id = UUID.randomUUID();
        BacktestDto dto = new BacktestDto(id, req.strategy(), symbols, req.from(), req.to(), Money.normalize(cash0),
                money(result.finalValue), pct((result.finalValue - cash0.doubleValue()) / cash0.doubleValue() * 100),
                pct(result.maxDrawdown * 100), pct(result.closedTrades == 0 ? 0 : 100.0 * result.wins / result.closedTrades),
                result.equity, result.trades, clock.instant());
        jdbc.update("INSERT INTO backtests (id, user_id, result, created_at) VALUES (?, ?, ?::jsonb, ?)", id, userId,
                json.writeValueAsString(dto), java.sql.Timestamp.from(dto.createdAt()));
        jdbc.update("""
                DELETE FROM backtests WHERE user_id = ? AND id NOT IN (
                  SELECT id FROM backtests WHERE user_id = ? ORDER BY created_at DESC LIMIT ?)
                """, userId, userId, KEEP);
        return dto;
    }

    @Transactional(readOnly = true)
    public List<BacktestDto> list(UUID userId) {
        return jdbc.query("SELECT result::text FROM backtests WHERE user_id = ? ORDER BY created_at DESC LIMIT ?",
                (rs, i) -> json.readValue(rs.getString(1), BacktestDto.class), userId, KEEP);
    }

    private List<String> symbols(List<String> raw) {
        if (raw == null || raw.isEmpty() || raw.size() > 5) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbols", "give 1 to 5 symbols");
        }
        List<String> out = new ArrayList<>();
        for (String r : raw) {
            String sym = MarketService.normalize(r);
            Boolean known = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM instruments WHERE symbol = ?)",
                    Boolean.class, sym);
            if (!Boolean.TRUE.equals(known) || out.contains(sym)) {
                throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "symbols", "unknown or repeated symbol " + sym);
            }
            out.add(sym);
        }
        return out;
    }

    // ------------------------------------------------------------------ strategies

    sealed interface Strategy permits SmaCross, Rsi, BuyTheDip, Rebalance {

        static Strategy parse(JsonNode n, List<String> symbols) {
            if (n == null || !n.isObject()) {
                throw bad("strategy", "is required");
            }
            String type = n.path("type").asString("");
            return switch (type) {
                case "SMA_CROSS" -> {
                    int fast = intIn(n, "fast", 2, 100);
                    int slow = intIn(n, "slow", fast + 1, 250);
                    yield new SmaCross(fast, slow);
                }
                case "RSI" -> {
                    int period = intIn(n, "period", 2, 50);
                    int buyBelow = intIn(n, "buyBelow", 1, 98);
                    yield new Rsi(period, buyBelow, intIn(n, "sellAbove", buyBelow + 1, 99));
                }
                case "BUY_THE_DIP" -> new BuyTheDip(decIn(n, "dropPct", 0.5, 50), decIn(n, "takeProfitPct", 0.5, 100));
                case "REBALANCE" -> {
                    int every = intIn(n, "everyDays", 1, 365);
                    JsonNode w = n.path("weights");
                    Map<String, Double> weights = new LinkedHashMap<>();
                    double sum = 0;
                    for (String sym : symbols) {
                        double v = w.path(sym).isMissingNode() ? -1 : decIn(w, sym, 0, 100);
                        if (v < 0) {
                            throw bad("strategy.weights", "needs a weight for every symbol");
                        }
                        weights.put(sym, v);
                        sum += v;
                    }
                    if (w.size() != symbols.size() || Math.abs(sum - 100) > 0.001) {
                        throw bad("strategy.weights", "must cover exactly the symbols and add up to 100");
                    }
                    yield new Rebalance(every, weights);
                }
                default -> throw bad("strategy.type", "must be SMA_CROSS, RSI, BUY_THE_DIP or REBALANCE");
            };
        }

        private static int intIn(JsonNode n, String field, int min, int max) {
            JsonNode v = n.path(field);
            if (!v.isIntegralNumber() || v.asInt() < min || v.asInt() > max) {
                throw bad("strategy." + field, "must be a whole number from " + min + " to " + max);
            }
            return v.asInt();
        }

        private static double decIn(JsonNode n, String field, double min, double max) {
            JsonNode v = n.path(field);
            double d;
            try {
                d = v.isNumber() ? v.asDouble() : Double.parseDouble(v.asString(""));
            } catch (NumberFormatException e) {
                d = Double.NaN;
            }
            if (Double.isNaN(d) || d < min || d > max) {
                throw bad("strategy." + field, "must be a number from " + min + " to " + max);
            }
            return d;
        }

        private static ApiException bad(String field, String message) {
            return ApiException.fieldError(HttpStatus.BAD_REQUEST, field, message);
        }
    }

    record SmaCross(int fast, int slow) implements Strategy {
    }

    record Rsi(int period, int buyBelow, int sellAbove) implements Strategy {
    }

    record BuyTheDip(double dropPct, double takeProfitPct) implements Strategy {
    }

    record Rebalance(int everyDays, Map<String, Double> weights) implements Strategy {
    }

    private record Result(double finalValue, double maxDrawdown, int wins, int closedTrades, List<EquityPoint> equity,
                          List<Trade> trades) {
    }

    /** Per-symbol position for the all-in/all-out strategies. */
    private static final class Pos {
        double cash;
        long shares;
        double entry;
        double high;
    }

    Result simulate(Strategy s, List<String> symbols, LocalDate from, LocalDate to, double cash0) {
        List<Trade> trades = new ArrayList<>();
        List<EquityPoint> equity = new ArrayList<>();
        int[] wins = {0};
        int[] closed = {0};
        double peak = cash0;
        double maxDd = 0;
        Map<String, Pos> pos = new HashMap<>();
        double rebalanceCash = cash0;
        Map<String, Long> rebalanceShares = new HashMap<>();
        Map<String, Double> rebalanceCost = new HashMap<>();
        for (String sym : symbols) {
            Pos p = new Pos();
            p.cash = cash0 / symbols.size();
            pos.put(sym, p);
            rebalanceShares.put(sym, 0L);
        }
        long totalDays = ChronoUnit.DAYS.between(from, to);
        long step = Math.max(1, (totalDays + 1 + MAX_POINTS - 1) / MAX_POINTS);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            long dayIndex = ChronoUnit.DAYS.between(from, d);
            double value;
            if (s instanceof Rebalance rb) {
                if (dayIndex % rb.everyDays() == 0) {
                    double total = rebalanceCash;
                    for (String sym : symbols) {
                        total += rebalanceShares.get(sym) * close(sym, d);
                    }
                    // Sells first (to free cash), then buys.
                    for (int pass = 0; pass < 2; pass++) {
                        for (String sym : symbols) {
                            double price = close(sym, d);
                            long target = (long) Math.floor(total * rb.weights().get(sym) / 100 / price);
                            long have = rebalanceShares.get(sym);
                            if (pass == 0 && target < have) {
                                long q = have - target;
                                double avg = have == 0 ? 0 : rebalanceCost.getOrDefault(sym, 0.0) / have;
                                rebalanceCash += q * price;
                                rebalanceCost.put(sym, rebalanceCost.getOrDefault(sym, 0.0) - avg * q);
                                rebalanceShares.put(sym, target);
                                closed[0]++;
                                if (price > avg) {
                                    wins[0]++;
                                }
                                trades.add(new Trade(d, sym, "SELL", q, money(price)));
                            } else if (pass == 1 && target > have) {
                                long q = Math.min(target - have, (long) Math.floor(rebalanceCash / price));
                                if (q > 0) {
                                    rebalanceCash -= q * price;
                                    rebalanceCost.merge(sym, q * price, Double::sum);
                                    rebalanceShares.put(sym, have + q);
                                    trades.add(new Trade(d, sym, "BUY", q, money(price)));
                                }
                            }
                        }
                    }
                }
                value = rebalanceCash;
                for (String sym : symbols) {
                    value += rebalanceShares.get(sym) * close(sym, d);
                }
            } else {
                value = 0;
                for (String sym : symbols) {
                    Pos p = pos.get(sym);
                    double price = close(sym, d);
                    p.high = Math.max(p.high, price);
                    Boolean signal = signal(s, sym, d, price, p);
                    if (Boolean.TRUE.equals(signal) && p.shares == 0) {
                        long q = (long) Math.floor(p.cash / price);
                        if (q > 0) {
                            p.shares = q;
                            p.cash -= q * price;
                            p.entry = price;
                            trades.add(new Trade(d, sym, "BUY", q, money(price)));
                        }
                    } else if (Boolean.FALSE.equals(signal) && p.shares > 0) {
                        p.cash += p.shares * price;
                        closed[0]++;
                        if (price > p.entry) {
                            wins[0]++;
                        }
                        trades.add(new Trade(d, sym, "SELL", p.shares, money(price)));
                        p.shares = 0;
                    }
                    value += p.cash + p.shares * price;
                }
            }
            peak = Math.max(peak, value);
            maxDd = Math.max(maxDd, peak == 0 ? 0 : (peak - value) / peak);
            if (dayIndex % step == 0 || d.equals(to)) {
                equity.add(new EquityPoint(d, money(value)));
            }
        }
        double finalValue = equity.getLast().value().doubleValue();
        return new Result(finalValue, maxDd, wins[0], closed[0], equity, trades);
    }

    /** TRUE = buy signal, FALSE = sell signal, null = hold. */
    private Boolean signal(Strategy s, String sym, LocalDate d, double price, Pos p) {
        return switch (s) {
            case SmaCross c -> {
                double fastNow = sma(sym, d, c.fast());
                double slowNow = sma(sym, d, c.slow());
                double fastPrev = sma(sym, d.minusDays(1), c.fast());
                double slowPrev = sma(sym, d.minusDays(1), c.slow());
                yield fastPrev <= slowPrev && fastNow > slowNow ? Boolean.TRUE
                        : fastPrev >= slowPrev && fastNow < slowNow ? Boolean.FALSE : null;
            }
            case Rsi r -> {
                double rsi = rsi(sym, d, r.period());
                yield rsi < r.buyBelow() ? Boolean.TRUE : rsi > r.sellAbove() ? Boolean.FALSE : null;
            }
            case BuyTheDip b -> {
                double recentHigh = 0;
                for (int i = 1; i <= 20; i++) {
                    recentHigh = Math.max(recentHigh, close(sym, d.minusDays(i)));
                }
                if (p.shares == 0) {
                    yield price <= recentHigh * (1 - b.dropPct() / 100) ? Boolean.TRUE : null;
                }
                yield price >= p.entry * (1 + b.takeProfitPct() / 100) ? Boolean.FALSE : null;
            }
            case Rebalance ignored -> null;
        };
    }

    private double sma(String sym, LocalDate d, int n) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += close(sym, d.minusDays(i));
        }
        return sum / n;
    }

    private double rsi(String sym, LocalDate d, int n) {
        double gain = 0;
        double loss = 0;
        for (int i = 0; i < n; i++) {
            double change = close(sym, d.minusDays(i)) - close(sym, d.minusDays(i + 1));
            if (change > 0) {
                gain += change;
            } else {
                loss -= change;
            }
        }
        return loss == 0 ? 100 : 100 - 100 / (1 + gain / loss);
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }

    private static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_EVEN);
    }
}
