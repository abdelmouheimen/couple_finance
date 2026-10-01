package com.couplefinance.shared.error;

import java.net.URI;
import java.util.List;

import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.ratelimit.RateLimitExceededException;
import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error as RFC 9457 Problem Details with a stable {@code code} property (architecture.md §7):
 *
 * <ul>
 *   <li>framework exceptions (malformed JSON, unknown route, wrong method…) via {@link ResponseEntityExceptionHandler};
 *   <li>bean validation failures as {@code VALIDATION_FAILED} with an {@code errors} array;
 *   <li>{@link ApplicationException} with its own code;
 *   <li>authentication / authorization failures, also those raised by the security filter chain
 *       (see {@code SecurityConfiguration});
 *   <li>anything else as {@code INTERNAL_ERROR}, without leaking the exception message.
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String CODE = "code";
    static final String ERRORS = "errors";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** A single invalid input. {@code field} is a request-body property path or a parameter name. */
    public record FieldViolation(String field, String message) {}

    @ExceptionHandler(ApplicationException.class)
    ResponseEntity<ProblemDetail> handleApplicationException(ApplicationException ex, HttpServletRequest request) {
        log.debug("Application error {}: {}", ex.errorCode().code(), ex.getMessage());
        return problem(ex.errorCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    ResponseEntity<ProblemDetail> handleRateLimited(RateLimitExceededException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(ex.errorCode(), ex.getMessage(), request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()))
                .body(response.getBody());
    }

    /** Persistence-level lost update (JPA {@code @Version}): same contract as a stale If-Match (BR-EXP-12). */
    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    ResponseEntity<ProblemDetail> handleOptimisticLock(Exception ex, HttpServletRequest request) {
        log.debug("Optimistic lock failure on {} {}", request.getMethod(), request.getRequestURI());
        ApplicationException conflict = VersionETag.versionConflict();
        return problem(conflict.errorCode(), conflict.getMessage(), request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        ResponseEntity<ProblemDetail> response = problem(CommonErrorCode.AUTHENTICATION_REQUIRED,
                "Authentication is required to access this resource.", request);
        return ResponseEntity.status(response.getStatusCode())
                .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                .body(response.getBody());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(CommonErrorCode.ACCESS_DENIED, "You are not allowed to perform this action.", request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(CommonErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getAllErrors().stream()
                .map(error -> new FieldViolation(
                        error instanceof FieldError fieldError ? fieldError.getField() : error.getObjectName(),
                        messageOf(error)))
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> violations = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldViolation(
                                error instanceof FieldError fieldError
                                        ? fieldError.getField()
                                        : String.valueOf(result.getMethodParameter().getParameterName()),
                                messageOf(error))))
                .toList();
        return handleExceptionInternal(ex, validationProblem(violations), headers, status, request);
    }

    /** Adds {@code code} and {@code instance} to every framework-generated problem. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            if (problem.getProperties() == null || !problem.getProperties().containsKey(CODE)) {
                problem.setProperty(CODE, CommonErrorCode.forStatus(statusCode).code());
            }
            if (problem.getInstance() == null && request instanceof ServletWebRequest servletRequest) {
                problem.setInstance(URI.create(servletRequest.getRequest().getRequestURI()));
            }
        }
        return response;
    }

    private static ProblemDetail validationProblem(List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                CommonErrorCode.VALIDATION_FAILED.status(), "The request contains invalid values.");
        problem.setProperty(CODE, CommonErrorCode.VALIDATION_FAILED.code());
        problem.setProperty(ERRORS, violations);
        return problem;
    }

    private static ResponseEntity<ProblemDetail> problem(ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setProperty(CODE, code.code());
        problem.setInstance(URI.create(request.getRequestURI()));
        return ResponseEntity.status(code.status()).body(problem);
    }

    private static String messageOf(MessageSourceResolvable error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid";
    }
}
