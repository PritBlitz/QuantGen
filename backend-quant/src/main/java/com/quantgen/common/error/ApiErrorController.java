package com.quantgen.common.error;

import java.util.List;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * Replaces Boot's default /error handling so that errors raised outside Spring MVC (in filters or
 * the servlet container) still produce the PRD §7.1 body.
 */
@Hidden
@RestController
public class ApiErrorController implements ErrorController {

    private final ApiErrorWriter writer;

    public ApiErrorController(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @RequestMapping("/error")
    ResponseEntity<ApiError> error(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        ErrorCode code = switch (status) {
            case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> ErrorCode.FORBIDDEN;
            case NOT_FOUND -> ErrorCode.NOT_FOUND;
            case TOO_MANY_REQUESTS -> ErrorCode.RATE_LIMITED;
            default -> status.is4xxClientError() ? ErrorCode.VALIDATION_FAILED : ErrorCode.INTERNAL_ERROR;
        };
        String message = code == ErrorCode.VALIDATION_FAILED ? "Bad request" : code.defaultMessage();
        return ResponseEntity.status(status).body(writer.build(request, status, code, message, List.of()));
    }

    private static HttpStatus resolveStatus(Object attribute) {
        if (attribute instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
