package com.quantgen.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import org.springframework.http.HttpStatus;

import com.quantgen.common.config.TimeConfig;
import com.quantgen.common.error.ApiException;

/**
 * An inclusive IST date range (PRD 12.13). Defaults to the last 30 days ending today. {@link #previous()} is the
 * period of equal length immediately before it, used for "vs previous period" comparisons.
 */
public record AnalyticsRange(LocalDate from, LocalDate to) {

    public static final int DEFAULT_DAYS = 30;
    public static final int MAX_DAYS_CUSTOMER = 366;
    public static final int MAX_DAYS_ADMIN = 366 * 3;

    public static AnalyticsRange parse(LocalDate from, LocalDate to, int maxDays, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        LocalDate end = to != null ? to : today;
        LocalDate start = from != null ? from : end.minusDays(DEFAULT_DAYS - 1L);
        if (start.isAfter(end)) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "must not be after 'to'");
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 > maxDays) {
            throw ApiException.fieldError(HttpStatus.BAD_REQUEST, "from", "range can be at most " + maxDays + " days");
        }
        return new AnalyticsRange(start, end);
    }

    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public AnalyticsRange previous() {
        LocalDate prevTo = from.minusDays(1);
        return new AnalyticsRange(prevTo.minusDays(days() - 1), prevTo);
    }

    /** Start of the first day, as an instant (inclusive lower bound). */
    public Instant lo() {
        return from.atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }

    /** Start of the day after the last day (exclusive upper bound). */
    public Instant hi() {
        return to.plusDays(1).atStartOfDay(TimeConfig.BUSINESS_ZONE).toInstant();
    }
}
