package com.quantgen.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** PRD 12.13 shapes. Every metric value is a decimal string, counts included. */
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public enum Unit { MONEY, COUNT, PERCENT }

    public enum Granularity { DAY, WEEK, MONTH }

    public enum Good { UP, DOWN }

    /** Metric keys for KPIs and series. {@code series} is false for KPI-only keys; {@code available} for v2 ones. */
    public enum Metric {
        TOTAL_USERS("Total users", Unit.COUNT, Good.UP, true, true),
        NEW_USERS("New users", Unit.COUNT, Good.UP, true, true),
        ACTIVE_USERS("Active users", Unit.COUNT, Good.UP, true, true),
        FUNDED_USERS_PCT("Funded users", Unit.PERCENT, Good.UP, true, false),
        CUSTOMER_DEPOSITS("Customer deposits", Unit.MONEY, Good.UP, true, true),
        NET_INFLOW("Net inflow", Unit.MONEY, Good.UP, true, true),
        TRANSFER_VOLUME("Transfer volume", Unit.MONEY, Good.UP, true, true),
        TRANSFER_COUNT("Transfers", Unit.COUNT, Good.UP, true, true),
        TRADE_VOLUME("Trade volume", Unit.MONEY, Good.UP, true, true),
        TRADE_COUNT("Trades", Unit.COUNT, Good.UP, true, true),
        OUTSTANDING_PRINCIPAL("Outstanding loan principal", Unit.MONEY, Good.UP, true, false),
        LOAN_DISBURSED("Loans disbursed", Unit.MONEY, Good.UP, true, true),
        LOAN_REPAID("Loan repayments", Unit.MONEY, Good.UP, true, true),
        INTEREST_INCOME("Interest income", Unit.MONEY, Good.UP, true, true),
        OVERDUE_AMOUNT("Overdue amount", Unit.MONEY, Good.DOWN, true, true),
        OVERDUE_RATE_PCT("Overdue rate", Unit.PERCENT, Good.DOWN, true, false),
        FAILED_MONEY_REQUESTS("Failed money requests", Unit.COUNT, Good.DOWN, true, true),
        COPILOT_TURNS("Copilot turns", Unit.COUNT, Good.UP, false, true),
        AI_COST_USD("AI cost (USD)", Unit.MONEY, Good.DOWN, false, true),
        AUTOPILOT_RUNS("Autopilot runs", Unit.COUNT, Good.UP, false, true),
        FRAUD_CASES("Fraud cases", Unit.COUNT, Good.DOWN, false, true);

        public final String label;
        public final Unit unit;
        public final Good good;
        /** False until the milestone that produces the data ships (the API still answers 200). */
        public final boolean available;
        /** True if it can be requested as a time series. */
        public final boolean series;

        Metric(String label, Unit unit, Good good, boolean available, boolean series) {
            this.label = label;
            this.unit = unit;
            this.good = good;
            this.available = available;
            this.series = series;
        }
    }

    public enum Dimension {
        TRANSACTION_TYPE, ACCOUNT_TYPE, ACCOUNT_STATUS, INSTRUMENT, SECTOR, ORDER_SIDE, LOAN_STATUS, LOAN_PURPOSE,
        FUNDING_SOURCE, ERROR_CODE, SPEND_CATEGORY
    }

    public record KpiDto(String key, String label, Unit unit, String value, String previous, String changePct,
                         Good goodDirection) {
    }

    public record OverviewDto(LocalDate from, LocalDate to, LocalDate previousFrom, LocalDate previousTo,
                              List<KpiDto> kpis) {
    }

    public record SeriesPoint(LocalDate t, String value) {
    }

    public record SeriesDto(String metric, String label, Unit unit, Granularity granularity, boolean available,
                            List<SeriesPoint> points, List<SeriesPoint> previous) {
    }

    public record BreakdownRow(String key, String label, String value, Long count, String sharePct,
                               boolean suppressed) {
    }

    public record BreakdownDto(String dimension, Unit unit, String total, List<BreakdownRow> rows) {
    }

    public record FunnelStep(String key, String label, String users, String conversionPct, String stepConversionPct) {
    }

    public record FunnelDto(List<FunnelStep> steps) {
    }

    public record CohortRow(String cohort, String size, List<String> retentionPct) {
    }

    public record CohortsDto(List<CohortRow> cohorts) {
    }

    public record AgeingBucket(String bucket, String count, String amount) {
    }

    public record StatusRow(String status, String count, String amount) {
    }

    public record LoanBookDto(String outstandingPrincipal, String disbursed, String repaid, String interestEarned,
                              String activeCount, String approvalRatePct, String avgTicket, String overdueAmount,
                              String overdueRatePct, List<AgeingBucket> ageing, List<StatusRow> byStatus) {
    }

    public record FeatureAdoption(String key, String label, String users, String pct, boolean available) {
    }

    public record AiAdoption(boolean available, String turns, String costUsd, String costPerActiveUser) {
    }

    public record AutopilotAdoption(boolean available, String runs, String successPct) {
    }

    public record FraudAdoption(boolean available, String cases, String releasedPct) {
    }

    public record AdoptionDto(List<FeatureAdoption> features, AiAdoption ai, AutopilotAdoption autopilot,
                              FraudAdoption fraud) {
    }

    public record HealthDto(boolean reconcileBalanced, Instant checkedAt, String sealLagSeconds, String outboxLag,
                            String moneyErrorRatePct) {
    }

    // ------------------------------------------------------------------ personal

    public record SummaryTotals(String income, String spending, String net, String savingsRatePct) {
    }

    public record TopCategory(String key, String label, String amount) {
    }

    public record LargestSpend(UUID transactionId, String amount, String description, LocalDate date) {
    }

    public record MoneySummaryDto(LocalDate from, LocalDate to, String income, String spending, String net,
                                  String savingsRatePct, SummaryTotals previous, TopCategory topCategory,
                                  LargestSpend largestSpend) {
    }

    public record CashflowPoint(String month, String income, String spending, String net, String invested,
                                String borrowed) {
    }

    public record CashflowDto(List<CashflowPoint> points) {
    }

    public record CounterpartyDto(String label, String accountNumberMasked, String sent, String received, long count) {
    }

    public record BalancePoint(LocalDate t, String balance) {
    }

    public record BalanceHistoryDto(UUID accountId, List<BalancePoint> points) {
    }

    public record PortfolioPoint(LocalDate t, String marketValue, String invested) {
    }

    public record AllocationRow(String sector, String value, String pct) {
    }

    public record HoldingPerf(String symbol, String name, String unrealizedPnl, String unrealizedPnlPct,
                              String realizedPnl) {
    }

    public record PerfMark(String symbol, String pnlPct) {
    }

    public record PortfolioDto(List<PortfolioPoint> points, String twrPct, List<AllocationRow> allocation,
                               List<HoldingPerf> holdings, PerfMark best, PerfMark worst) {
    }

    public record LoanProgress(UUID loanId, String principalPaid, String interestPaid, String remainingPrincipal,
                               LocalDate projectedPayoffDate) {
    }

    public record LoanAnalyticsDto(String totalInterestPaid, List<LoanProgress> loans) {
    }

    public record PrepaymentDto(String interestSaved, int monthsSaved, LocalDate newPayoffDate) {
    }

    // ------------------------------------------------------------------ formatting

    public static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_EVEN).toPlainString();
    }

    public static String count(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    public static String pct(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** num / den * 100, or null when den is zero. */
    public static BigDecimal ratioPct(BigDecimal num, BigDecimal den) {
        if (den == null || den.signum() == 0) {
            return null;
        }
        return num.multiply(BigDecimal.valueOf(100)).divide(den, 6, RoundingMode.HALF_UP);
    }

    /** (current - previous) / |previous| * 100, or null when previous is zero. */
    public static String changePct(BigDecimal current, BigDecimal previous) {
        if (previous.signum() == 0) {
            return null;
        }
        return pct(current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous.abs(), 6, RoundingMode.HALF_UP));
    }

    public static String format(Unit unit, BigDecimal v) {
        return switch (unit) {
            case MONEY -> money(v);
            case COUNT -> count(v);
            case PERCENT -> pct(v);
        };
    }
}
