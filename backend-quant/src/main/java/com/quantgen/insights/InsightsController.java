package com.quantgen.insights;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.account.api.TransactionDto;
import com.quantgen.analytics.AnalyticsRange;
import com.quantgen.common.security.AuthUser;
import com.quantgen.insights.InsightsService.BudgetDto;
import com.quantgen.insights.InsightsService.BudgetItem;
import com.quantgen.insights.InsightsService.GroupBy;
import com.quantgen.insights.InsightsService.SpendingRow;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.5 insights: spending, recategorization and budgets (F-INS-1/2). */
@RestController
@Tag(name = "Insights")
public class InsightsController {

    public record CategoryRequest(@NotNull String category, Boolean applyToCounterparty) {
    }

    private final InsightsService insights;
    private final PlanningService planning;
    private final Clock clock;

    public InsightsController(InsightsService insights, PlanningService planning, Clock clock) {
        this.insights = insights;
        this.planning = planning;
        this.clock = clock;
    }

    @GetMapping("/api/v1/insights/forecast")
    @Operation(summary = "Projected spendable balance (savings + checking) for each of the next 1–90 days, from known "
            + "future items: EMIs, scheduled transfers, FD payouts and detected salary")
    public PlanningService.ForecastDto forecast(@AuthenticationPrincipal AuthUser user,
                                                @RequestParam(defaultValue = "30") int days) {
        return planning.forecast(user.id(), days);
    }

    @GetMapping("/api/v1/insights/net-worth")
    @Operation(summary = "Cash + goals + FDs + portfolio − outstanding loan principal, now and at each month end")
    public PlanningService.NetWorthDto netWorth(@AuthenticationPrincipal AuthUser user,
                                                @RequestParam(defaultValue = "12") int months) {
        return planning.netWorth(user.id(), months);
    }

    @GetMapping("/api/v1/insights/spending")
    @Operation(summary = "Spending (payments to others, bills, loan repayments) by category or month. Dates are IST "
            + "days, both inclusive; default the last 30 days, at most 366.")
    public List<SpendingRow> spending(@AuthenticationPrincipal AuthUser user,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(defaultValue = "category") GroupBy groupBy) {
        return insights.spending(user.id(), AnalyticsRange.parse(from, to, AnalyticsRange.MAX_DAYS_CUSTOMER, clock),
                groupBy);
    }

    @PatchMapping("/api/v1/transactions/{id}/category")
    @Operation(summary = "Recategorize one of your transactions; applyToCounterparty remembers it for every payment "
            + "to or from the same person.")
    public TransactionDto recategorize(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                       @Valid @RequestBody CategoryRequest request) {
        return insights.recategorize(user.id(), id, request.category(),
                Boolean.TRUE.equals(request.applyToCounterparty()));
    }

    @GetMapping("/api/v1/budgets")
    @Operation(summary = "Your monthly budgets with this month's spending")
    public List<BudgetDto> budgets(@AuthenticationPrincipal AuthUser user) {
        return insights.budgets(user.id());
    }

    @PutMapping("/api/v1/budgets")
    @Operation(summary = "Replace all your monthly budgets (an empty list removes them)")
    public List<BudgetDto> replaceBudgets(@AuthenticationPrincipal AuthUser user,
                                          @RequestBody @NotNull List<BudgetItem> budgets) {
        return insights.replaceBudgets(user.id(), budgets);
    }
}
