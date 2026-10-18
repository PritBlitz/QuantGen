package com.quantgen.common.error;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import com.quantgen.common.metrics.MoneyFailureRecorder;
import com.quantgen.common.web.RequestIdFilter;

import tools.jackson.databind.json.JsonMapper;

/** Builds {@link ApiError} bodies, and writes them directly for code paths outside Spring MVC. */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final MoneyFailureRecorder failures;

    public ApiErrorWriter(JsonMapper jsonMapper, Clock clock, MoneyFailureRecorder failures) {
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.failures = failures;
    }

    public ApiError build(HttpServletRequest request, HttpStatus status, ErrorCode code, String message,
                          List<ApiError.FieldError> fieldErrors) {
        return build(request, status, code, message, fieldErrors, null);
    }

    public ApiError build(HttpServletRequest request, HttpStatus status, ErrorCode code, String message,
                          List<ApiError.FieldError> fieldErrors, ApiError.StepUpChallenge stepUp) {
        failures.record(request, status.value(), code.name());
        return new ApiError(Instant.now(clock).truncatedTo(ChronoUnit.MILLIS), status.value(), code.name(), message, pathOf(request),
                fieldErrors, RequestIdFilter.currentRequestId(request), stepUp);
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        write(request, response, code.status(), code, code.defaultMessage());
    }

    public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, ErrorCode code,
                      String message) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        ApiError body = build(request, status, code, message, List.of());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        jsonMapper.writeValue(response.getOutputStream(), body);
    }

    private static String pathOf(HttpServletRequest request) {
        Object forwarded = request.getAttribute("jakarta.servlet.error.request_uri");
        return forwarded instanceof String uri ? uri : request.getRequestURI();
    }
}
