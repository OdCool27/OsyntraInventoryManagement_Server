package org.osyntra.osyntrainventorymanagement.controllers;

import jakarta.validation.ConstraintViolationException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final ApiProblems problems;

    public ApiExceptionHandler(ApiProblems problems) {
        this.problems = problems;
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ProblemDetail status(ResponseStatusException exception, HttpServletRequest request, HttpServletResponse response) {
        if (exception.getStatusCode().value() == 429) response.setHeader("Retry-After", "900");
        if (exception.getStatusCode().value() == 401) response.setHeader("WWW-Authenticate", "Bearer realm=\"Orvexa\"");
        return problems.create(exception.getStatusCode(),
                exception.getStatusCode().is5xxServerError() ? "Request could not be completed"
                        : exception.getReason() == null ? "Request failed" : exception.getReason(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        ProblemDetail problem = problems.create(HttpStatus.BAD_REQUEST, "Request validation failed", request);
        problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage()).toList());
        return problem;
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class, HandlerMethodValidationException.class})
    public ProblemDetail invalidRequest(Exception exception, HttpServletRequest request) {
        return problems.create(HttpStatus.BAD_REQUEST, "Invalid request fields or parameters", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail conflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.CONFLICT,
                "A unique value already exists, a reference is invalid, or a database constraint was violated", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail forbidden(AccessDeniedException exception, HttpServletRequest request) {
        return problems.create(HttpStatus.FORBIDDEN, "Action is not permitted", request);
    }

    @ExceptionHandler(DataAccessException.class)
    public ProblemDetail database(DataAccessException exception, HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Retry-After", "1");
        return problems.create(HttpStatus.SERVICE_UNAVAILABLE, "Database operation could not be completed; retry with the same request ID for a retryable command", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail unexpected(Exception exception, HttpServletRequest request) {
        // Preserve framework 404/405/415 semantics without exposing arbitrary exception messages.
        if (exception instanceof ErrorResponse error && !error.getStatusCode().is5xxServerError()) {
            HttpStatus status = HttpStatus.resolve(error.getStatusCode().value());
            return problems.create(error.getStatusCode(), status == null ? "Request failed" : status.getReasonPhrase(), request);
        }
        var problem = problems.create(HttpStatus.INTERNAL_SERVER_ERROR, "Request could not be completed", request);
        // Exception messages may contain SQL values or provider secrets; log only classification and correlation.
        LOG.error("Request failed: requestId={}, exceptionType={}", problem.getProperties().get("requestId"), exception.getClass().getName());
        return problem;
    }
}
