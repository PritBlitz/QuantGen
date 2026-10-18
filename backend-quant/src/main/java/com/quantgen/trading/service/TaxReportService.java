package com.quantgen.trading.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
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

/**
 * F-TRD-11 tax P&L per Indian financial year (1 April – 31 March). Every filled order is replayed in fill order and
 * sells are matched to earlier buys first-in-first-out (as Indian tax rules require; the portfolio's own realized
 * P&L uses average cost, so the two can differ). A lot sold more than 12 months after it was bought is LONG term.
 * Costs and proceeds are split across lots in proportion to quantity; the last share of a lot takes the remainder,
 * so totals are exact.
 */
@Service
public class TaxReportService {

    public record Row(String symbol, LocalDate buyDate, LocalDate sellDate, long quantity, BigDecimal cost,
                      BigDecimal proceeds, BigDecimal gain, String term) {
    }

    public record TaxPnlDto(String fy, BigDecimal shortTermGains, BigDecimal longTermGains, BigDecimal total,
                            List<Row> rows) {
    }

    private record Fill(String symbol, String side, long quantity, BigDecimal total, LocalDate day) {
    }

    private static final class Lot {
        final LocalDate day;
        long quantity;
        BigDecimal cost;

        Lot(LocalDate day, long quantity, BigDecimal cost) {
            this.day = day;
            this.quantity = quantity;
            this.cost = cost;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public TaxReportService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** "2026-27" for the FY containing {@code day}. */
    static String fyOf(LocalDate day) {
        int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    @Transactional(readOnly = true)
    public TaxPnlDto report(UUID userId, String rawFy) {
        String fy = rawFy == null || rawFy.isBlank() ? fyOf(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)))
                : rawFy.trim();
        if (!fy.matches("^20\\d{2}-\\d{2}$")
                || Integer.parseInt(fy.substring(5)) != (Integer.parseInt(fy.substring(0, 4)) + 1) % 100) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "fy", "must look like 2026-27");
        }
        LocalDate start = LocalDate.of(Integer.parseInt(fy.substring(0, 4)), 4, 1);
        LocalDate end = start.plusYears(1); // exclusive
        List<Fill> fills = jdbc.query("""
                SELECT symbol, side, quantity, total, filled_at FROM orders
                 WHERE user_id = ? AND status = 'FILLED' AND filled_at < ? ORDER BY filled_at, created_at, id
                """, (rs, i) -> new Fill(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getBigDecimal(4),
                rs.getTimestamp(5).toInstant().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate()), userId,
                Timestamp.from(end.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant()));
        Map<String, Deque<Lot>> lots = new HashMap<>();
        List<Row> rows = new ArrayList<>();
        for (Fill f : fills) {
            Deque<Lot> q = lots.computeIfAbsent(f.symbol(), k -> new ArrayDeque<>());
            if ("BUY".equals(f.side())) {
                q.addLast(new Lot(f.day(), f.quantity(), f.total()));
                continue;
            }
            long left = f.quantity();
            BigDecimal proceedsLeft = f.total();
            while (left > 0 && !q.isEmpty()) {
                Lot lot = q.peekFirst();
                long take = Math.min(left, lot.quantity);
                BigDecimal cost = take == lot.quantity ? lot.cost : share(lot.cost, take, lot.quantity);
                BigDecimal proceeds = take == left ? proceedsLeft : share(proceedsLeft, take, left);
                if (!f.day().isBefore(start)) {
                    String term = f.day().isAfter(lot.day.plusMonths(12)) ? "LONG" : "SHORT";
                    rows.add(new Row(f.symbol(), lot.day, f.day(), take, money(cost), money(proceeds),
                            money(proceeds.subtract(cost)), term));
                }
                lot.quantity -= take;
                lot.cost = lot.cost.subtract(cost);
                if (lot.quantity == 0) {
                    q.pollFirst();
                }
                left -= take;
                proceedsLeft = proceedsLeft.subtract(proceeds);
            }
        }
        BigDecimal st = rows.stream().filter(r -> r.term().equals("SHORT")).map(Row::gain)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lt = rows.stream().filter(r -> r.term().equals("LONG")).map(Row::gain)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new TaxPnlDto(fy, money(st), money(lt), money(st.add(lt)), rows);
    }

    public String csv(TaxPnlDto r) {
        StringBuilder sb = new StringBuilder("symbol,buyDate,sellDate,quantity,cost,proceeds,gain,term\n");
        for (Row row : r.rows()) {
            sb.append(row.symbol()).append(',').append(row.buyDate()).append(',').append(row.sellDate()).append(',')
                    .append(row.quantity()).append(',').append(Money.toJson(row.cost())).append(',')
                    .append(Money.toJson(row.proceeds())).append(',').append(Money.toJson(row.gain())).append(',')
                    .append(row.term()).append('\n');
        }
        sb.append("TOTAL,,,,,,").append(Money.toJson(r.total())).append(",\n");
        return sb.toString();
    }

    private static BigDecimal share(BigDecimal amount, long part, long whole) {
        return amount.multiply(BigDecimal.valueOf(part)).divide(BigDecimal.valueOf(whole), Money.STORAGE_SCALE,
                RoundingMode.HALF_EVEN);
    }

    private static BigDecimal money(BigDecimal v) {
        return Money.roundToPaise(v);
    }
}
