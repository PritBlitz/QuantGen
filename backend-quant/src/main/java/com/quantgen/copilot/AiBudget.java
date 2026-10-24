package com.quantgen.copilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.error.ErrorCode;

/**
 * D-039 spend controls, in tokens (D-044: the free tier costs nothing, but its quota is small): a daily budget per
 * user and a global monthly cap that stops every turn when reached. Prices are config, so a paid provider later only
 * changes numbers; at 0 every cost is 0.
 */
@Component
public class AiBudget {

    public record AgentUsage(String agent, long runs, BigDecimal costUsd) {
    }

    public record DayUsage(LocalDate date, BigDecimal costUsd, long turns) {
    }

    public record Budget(BigDecimal monthlyCapUsd, BigDecimal usedPct) {
    }

    public record UsageDto(long tokens, BigDecimal costUsd, List<AgentUsage> byAgent, List<DayUsage> byDay,
                           Budget budget) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long userDailyTokens;
    private final long monthlyTokens;
    private final BigDecimal inputPerMillion;
    private final BigDecimal outputPerMillion;
    private final BigDecimal monthlyCapUsd;

    AiBudget(JdbcTemplate jdbc, Clock clock,
             @Value("${qg.ai.user-daily-tokens:200000}") long userDailyTokens,
             @Value("${qg.ai.monthly-tokens:5000000}") long monthlyTokens,
             @Value("${qg.ai.price-input-per-million:0}") BigDecimal inputPerMillion,
             @Value("${qg.ai.price-output-per-million:0}") BigDecimal outputPerMillion,
             @Value("${qg.ai.monthly-cap-usd:0}") BigDecimal monthlyCapUsd) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.userDailyTokens = userDailyTokens;
        this.monthlyTokens = monthlyTokens;
        this.inputPerMillion = inputPerMillion;
        this.outputPerMillion = outputPerMillion;
        this.monthlyCapUsd = monthlyCapUsd;
    }

    /** 429 BUDGET_EXHAUSTED when this user's tokens today, or everyone's this month, reached the limit. */
    public void requireAvailable(UUID userId) {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        if (tokens("user_id = ? AND started_at >= ?", userId, start(today)) >= userDailyTokens) {
            throw new ApiException(ErrorCode.BUDGET_EXHAUSTED, "You've used today's assistant budget; it resets at midnight");
        }
        if (monthTokens() >= monthlyTokens) {
            throw new ApiException(ErrorCode.BUDGET_EXHAUSTED, "The assistant's monthly budget is used up");
        }
    }

    public BigDecimal cost(long input, long output) {
        return inputPerMillion.multiply(BigDecimal.valueOf(input)).add(outputPerMillion.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    /** Admin usage over an inclusive IST date range (default: the last 30 days). */
    public UsageDto usage(LocalDate from, LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate begin = from != null ? from : end.minusDays(29);
        if (begin.isAfter(end) || begin.plusDays(366).isBefore(end)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must be before 'to', at most 366 days apart");
        }
        Timestamp a = start(begin);
        Timestamp b = start(end.plusDays(1));
        Map<String, Object> total = jdbc.queryForMap("SELECT COALESCE(sum(input_tokens + output_tokens), 0) AS t, "
                + "COALESCE(sum(cost_usd), 0) AS c FROM agent_runs WHERE started_at >= ? AND started_at < ?", a, b);
        List<AgentUsage> byAgent = jdbc.query("""
                SELECT s.agent, count(DISTINCT s.run_id) AS runs, COALESCE(sum(s.input_tokens), 0) AS i,
                       COALESCE(sum(s.output_tokens), 0) AS o
                  FROM agent_steps s JOIN agent_runs r ON r.id = s.run_id
                 WHERE r.started_at >= ? AND r.started_at < ? GROUP BY s.agent ORDER BY s.agent
                """, (rs, i) -> new AgentUsage(rs.getString(1), rs.getLong(2), money(cost(rs.getLong(3), rs.getLong(4)))),
                a, b);
        List<DayUsage> byDay = jdbc.query("""
                SELECT (started_at AT TIME ZONE 'Asia/Kolkata')::date AS d, COALESCE(sum(cost_usd), 0) AS c,
                       count(*) FILTER (WHERE kind = 'CHAT') AS turns
                  FROM agent_runs WHERE started_at >= ? AND started_at < ? GROUP BY 1 ORDER BY 1
                """, (rs, i) -> new DayUsage(rs.getObject(1, LocalDate.class), money(rs.getBigDecimal(2)), rs.getLong(3)),
                a, b);
        BigDecimal usedPct = monthlyTokens <= 0 ? BigDecimal.ZERO : BigDecimal.valueOf(monthTokens())
                .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(monthlyTokens), 1, RoundingMode.HALF_UP);
        return new UsageDto(((Number) total.get("t")).longValue(), money((BigDecimal) total.get("c")), byAgent, byDay,
                new Budget(money(monthlyCapUsd), usedPct));
    }

    private long monthTokens() {
        YearMonth m = YearMonth.from(LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)));
        return tokens("started_at >= ?", null, start(m.atDay(1)));
    }

    private long tokens(String where, UUID userId, Timestamp since) {
        Object[] args = userId == null ? new Object[] {since} : new Object[] {userId, since};
        Long t = jdbc.queryForObject("SELECT COALESCE(sum(input_tokens + output_tokens), 0) FROM agent_runs WHERE "
                + where, Long.class, args);
        return t == null ? 0 : t;
    }

    private static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static Timestamp start(LocalDate day) {
        return Timestamp.from(day.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant());
    }
}
