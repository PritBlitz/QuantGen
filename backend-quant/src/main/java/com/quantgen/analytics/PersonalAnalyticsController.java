package com.quantgen.analytics;

import static com.quantgen.analytics.AnalyticsDtos.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.money.MoneyAmount;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** PRD 12.13 personal analytics: the caller's own data only. */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "Analytics")
public class PersonalAnalyticsController {

    public record PrepaymentRequest(@NotNull @MoneyAmount(min = "1.00", max = "10000000.00") BigDecimal amount) {
    }

    private final PersonalAnalyticsService analytics;
    private final Clock clock;

    public PersonalAnalyticsController(PersonalAnalyticsService analytics, Clock clock) {
        this.analytics = analytics;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public MoneySummaryDto summary(@AuthenticationPrincipal AuthUser user,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.summary(user.id(), range(from, to));
    }

    @GetMapping("/cashflow")
    public CashflowDto cashflow(@AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "12") int months) {
        return analytics.cashflow(user.id(), months);
    }

    @GetMapping("/counterparties")
    public List<CounterpartyDto> counterparties(@AuthenticationPrincipal AuthUser user,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                @RequestParam(defaultValue = "5") int limit) {
        return analytics.counterparties(user.id(), range(from, to), limit);
    }

    @GetMapping("/balance-history")
    public BalanceHistoryDto balanceHistory(@AuthenticationPrincipal AuthUser user,
                                            @RequestParam(required = false) UUID accountId,
                                            @RequestParam(defaultValue = "1M") String range) {
        return analytics.balanceHistory(user.id(), accountId, range);
    }

    @GetMapping("/portfolio")
    public PortfolioDto portfolio(@AuthenticationPrincipal AuthUser user, @RequestParam(defaultValue = "1M") String range) {
        return analytics.portfolio(user.id(), range);
    }

    @GetMapping("/loans")
    public LoanAnalyticsDto loans(@AuthenticationPrincipal AuthUser user) {
        return analytics.loans(user.id());
    }

    @PostMapping("/loans/{id}/prepayment")
    public PrepaymentDto prepayment(@AuthenticationPrincipal AuthUser user, @PathVariable UUID id,
                                    @Valid @RequestBody PrepaymentRequest request) {
        return analytics.prepayment(user.id(), id, request.amount());
    }

    @GetMapping("/goals")
    public List<AnalyticsDtos.GoalProgressDto> goals(@AuthenticationPrincipal AuthUser user) {
        return analytics.goals(user.id());
    }

    private AnalyticsRange range(LocalDate from, LocalDate to) {
        return AnalyticsRange.parse(from, to, AnalyticsRange.MAX_DAYS_CUSTOMER, clock);
    }
}
