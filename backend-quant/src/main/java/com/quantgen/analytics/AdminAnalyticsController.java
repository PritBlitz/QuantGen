package com.quantgen.analytics;

import static com.quantgen.analytics.AnalyticsDtos.*;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.quantgen.common.audit.AuditService;
import com.quantgen.common.error.ApiException;
import com.quantgen.common.security.AuthUser;

import io.swagger.v3.oas.annotations.tags.Tag;

/** PRD 12.13 business analytics. Path is under /admin, so SecurityConfig already requires role ADMIN. */
@RestController
@RequestMapping("/api/v1/admin/analytics")
@Tag(name = "Analytics")
public class AdminAnalyticsController {

    private final AdminAnalyticsService analytics;
    private final AuditService audit;
    private final Clock clock;

    public AdminAnalyticsController(AdminAnalyticsService analytics, AuditService audit, Clock clock) {
        this.analytics = analytics;
        this.audit = audit;
        this.clock = clock;
    }

    @GetMapping("/overview")
    public OverviewDto overview(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.overview(range(from, to));
    }

    @GetMapping("/timeseries")
    public SeriesDto timeseries(@RequestParam String metric,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "DAY") String granularity,
                                @RequestParam(defaultValue = "false") boolean compare) {
        return analytics.timeseries(parse(Metric.class, "metric", metric), range(from, to),
                parse(Granularity.class, "granularity", granularity), compare);
    }

    @GetMapping("/breakdown")
    public BreakdownDto breakdown(@RequestParam String dimension,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                  @RequestParam(defaultValue = "10") int limit) {
        return analytics.breakdown(parse(Dimension.class, "dimension", dimension), range(from, to), limit);
    }

    @GetMapping("/funnel")
    public FunnelDto funnel(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.funnel(range(from, to));
    }

    @GetMapping("/cohorts")
    public CohortsDto cohorts(@RequestParam(defaultValue = "6") int months) {
        return analytics.cohorts(months);
    }

    @GetMapping("/loan-book")
    public LoanBookDto loanBook() {
        return analytics.loanBook();
    }

    @GetMapping("/adoption")
    public AdoptionDto adoption(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return analytics.adoption(range(from, to));
    }

    @GetMapping("/health")
    public HealthDto health() {
        return analytics.health();
    }

    /** F-ANL-9: CSV of one report. Every export is audited, with the report name and range but never the data. */
    @GetMapping(value = "/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal AuthUser admin, @RequestParam String report,
                                         @RequestParam(required = false) String metric,
                                         @RequestParam(required = false) String dimension,
                                         @RequestParam(defaultValue = "DAY") String granularity,
                                         @RequestParam(defaultValue = "false") boolean compare,
                                         @RequestParam(defaultValue = "10") int limit,
                                         @RequestParam(defaultValue = "6") int months,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AnalyticsRange range = range(from, to);
        List<List<String>> rows = new ArrayList<>();
        String name = report.toLowerCase(Locale.ROOT);
        switch (name) {
            case "overview" -> {
                rows.add(List.of("key", "label", "unit", "value", "previous", "changePct"));
                for (KpiDto k : analytics.overview(range).kpis()) {
                    rows.add(Csv.row(k.key(), k.label(), k.unit(), k.value(), k.previous(), k.changePct()));
                }
            }
            case "timeseries" -> {
                if (metric == null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "metric", "is required for this report");
                }
                SeriesDto s = analytics.timeseries(parse(Metric.class, "metric", metric), range,
                        parse(Granularity.class, "granularity", granularity), compare);
                rows.add(List.of("t", "value"));
                s.points().forEach(p -> rows.add(Csv.row(p.t(), p.value())));
            }
            case "breakdown" -> {
                if (dimension == null) {
                    throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "dimension", "is required for this report");
                }
                BreakdownDto b = analytics.breakdown(parse(Dimension.class, "dimension", dimension), range, limit);
                rows.add(List.of("key", "label", "value", "count", "sharePct", "suppressed"));
                b.rows().forEach(r -> rows.add(Csv.row(r.key(), r.label(), r.value(), r.count(), r.sharePct(),
                        r.suppressed())));
            }
            case "funnel" -> {
                rows.add(List.of("key", "label", "users", "conversionPct", "stepConversionPct"));
                analytics.funnel(range).steps().forEach(st -> rows.add(Csv.row(st.key(), st.label(), st.users(),
                        st.conversionPct(), st.stepConversionPct())));
            }
            case "cohorts" -> {
                rows.add(List.of("cohort", "size", "retentionPct (month 0, 1, ...)"));
                analytics.cohorts(months).cohorts().forEach(c -> {
                    List<String> row = new ArrayList<>(List.of(c.cohort(), c.size()));
                    c.retentionPct().forEach(v -> row.add(v == null ? "" : v));
                    rows.add(Csv.row(row.toArray()));
                });
            }
            case "loan-book" -> {
                LoanBookDto lb = analytics.loanBook();
                rows.add(List.of("metric", "value"));
                rows.add(Csv.row("outstandingPrincipal", lb.outstandingPrincipal()));
                rows.add(Csv.row("disbursed", lb.disbursed()));
                rows.add(Csv.row("repaid", lb.repaid()));
                rows.add(Csv.row("interestEarned", lb.interestEarned()));
                rows.add(Csv.row("activeCount", lb.activeCount()));
                rows.add(Csv.row("approvalRatePct", lb.approvalRatePct()));
                rows.add(Csv.row("avgTicket", lb.avgTicket()));
                rows.add(Csv.row("overdueAmount", lb.overdueAmount()));
                rows.add(Csv.row("overdueRatePct", lb.overdueRatePct()));
                lb.ageing().forEach(a -> rows.add(Csv.row("ageing " + a.bucket() + " count", a.count())));
                lb.ageing().forEach(a -> rows.add(Csv.row("ageing " + a.bucket() + " amount", a.amount())));
            }
            default -> throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "report",
                    "must be one of overview, timeseries, breakdown, funnel, cohorts, loan-book");
        }
        audit.record(admin.id(), "ANALYTICS_EXPORTED", "ANALYTICS", name,
                "from=" + range.from() + " to=" + range.to() + " rows=" + (rows.size() - 1));
        byte[] body = Csv.render(rows).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("quantgen-" + name + "-" + range.from() + "_" + range.to() + ".csv").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private AnalyticsRange range(LocalDate from, LocalDate to) {
        return AnalyticsRange.parse(from, to, AnalyticsRange.MAX_DAYS_ADMIN, clock);
    }

    static <E extends Enum<E>> E parse(Class<E> type, String field, String value) {
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, field, "is not a supported value");
        }
    }
}
