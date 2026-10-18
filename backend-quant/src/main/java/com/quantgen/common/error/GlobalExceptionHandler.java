package com.quantgen.common.error;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Maps every exception to the PRD §7.1 error body. Never leaks stack traces, SQL or class names:
 * unexpected exceptions are logged server-side and answered with a generic INTERNAL_ERROR.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ApiErrorWriter writer;

    public GlobalExceptionHandler(ApiErrorWriter writer) {
        this.writer = writer;
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApi(ApiException ex, HttpServletRequest req) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.status());
        if (ex.retryAfterSeconds() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(writer.build(req, ex.status(), ex.code(), ex.getMessage(), ex.fieldErrors(), ex.stepUp()));
    }

    /** Also covers MethodArgumentNotValidException (invalid @Valid request bodies). */
    @ExceptionHandler(BindException.class)
    ResponseEntity<ApiError> handleBind(BindException ex, HttpServletRequest req) {
        Stream<ApiError.FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .sorted(Comparator.comparing(ApiError.FieldError::field));
        Stream<ApiError.FieldError> global = ex.getBindingResult().getGlobalErrors().stream()
                .map(ge -> new ApiError.FieldError(ge.getObjectName(), ge.getDefaultMessage()));
        return validation(req, Stream.concat(fields, global).toList());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r.getResolvableErrors().stream()
                        .map(e -> new ApiError.FieldError(parameterName(r.getMethodParameter().getParameterName()),
                                e.getDefaultMessage())))
                .toList();
        return validation(req, fields);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        List<ApiError.FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new ApiError.FieldError(lastNode(v.getPropertyPath().toString()), v.getMessage()))
                .toList();
        return validation(req, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return error(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "Malformed or unreadable request body");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        return validation(req, List.of(new ApiError.FieldError(ex.getHeaderName(), "header is required")));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ApiError> handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest req) {
        return validation(req, List.of(new ApiError.FieldError(ex.getParameterName(), "is required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return validation(req, List.of(new ApiError.FieldError(ex.getName(), "has an invalid value")));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return error(req, HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.defaultMessage());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> handleMethod(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return error(req, HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.VALIDATION_FAILED, "HTTP method not supported");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest req) {
        return error(req, HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.VALIDATION_FAILED, "Unsupported content type");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<ApiError> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        return error(req, HttpStatus.NOT_ACCEPTABLE, ErrorCode.VALIDATION_FAILED, "Requested media type not available");
    }

    /** Also covers AuthorizationDeniedException from method security. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return error(req, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, ErrorCode.FORBIDDEN.defaultMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        return error(req, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, ErrorCode.UNAUTHORIZED.defaultMessage());
    }

    @ExceptionHandler(ConcurrencyFailureException.class)
    ResponseEntity<ApiError> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest req) {
        log.warn("Concurrency conflict on {}: {}", req.getRequestURI(), ex.getClass().getSimpleName());
        return error(req, HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, "The resource was modified concurrently; retry");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        // Services pre-validate, so reaching this means a race or a DB guard fired. Never echo SQL details.
        log.warn("Data integrity violation on {}", req.getRequestURI(), ex);
        return error(req, HttpStatus.CONFLICT, ErrorCode.INVALID_STATE, ErrorCode.INVALID_STATE.defaultMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return error(req, HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                ErrorCode.INTERNAL_ERROR.defaultMessage());
    }

    private ResponseEntity<ApiError> validation(HttpServletRequest req, List<ApiError.FieldError> fields) {
        return ResponseEntity.badRequest().body(writer.build(req, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                ErrorCode.VALIDATION_FAILED.defaultMessage(), fields));
    }

    private ResponseEntity<ApiError> error(HttpServletRequest req, HttpStatus status, ErrorCode code, String message) {
        return ResponseEntity.status(status).body(writer.build(req, status, code, message, List.of()));
    }

    private static String lastNode(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 ? path.substring(dot + 1) : path;
    }

    private static String parameterName(String name) {
        return name == null ? "request" : name;
    }
}
