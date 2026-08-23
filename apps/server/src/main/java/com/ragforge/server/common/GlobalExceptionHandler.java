package com.ragforge.server.common;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> handleApi(ApiException exception, HttpServletRequest request) {
        return build(exception.status().value(), exception.code(), exception.title(), exception.getMessage(), request,
                null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                    HttpServletRequest request) {
        List<Map<String, String>> fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of("field", error.getField(), "code", "INVALID_FIELD",
                        "message", "invalid value"))
                .toList();
        return build(400, "validation_failed", "Validation failed", "Request validation failed", request, fields);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> handleConflict(DataIntegrityViolationException exception,
                                                  HttpServletRequest request) {
        log.error("Data integrity conflict correlationId={} message={}",
                CorrelationIdFilter.current(request), safeMessage(exception));
        return build(409, "conflict", "Conflict", "The request conflicts with existing data", request, null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled API exception correlationId={} type={} message={}",
                CorrelationIdFilter.current(request), exception.getClass().getName(), safeMessage(exception));
        return build(500, "internal_error", "Internal server error", "The request could not be completed", request,
                null);
    }

    private static String safeMessage(Exception exception) {
        String value = exception.getMessage() == null ? "no-message" : exception.getMessage();
        return value.replaceAll("(?i)(password|secret|token|api[_-]?key|credential)[^,; ]*", "$1=[REDACTED]")
                .substring(0, Math.min(500, value.length()));
    }

    private ResponseEntity<ProblemDetail> build(int status, String code, String title, String detail,
                                                HttpServletRequest request, Object fields) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        problem.setType(URI.create("https://ragforge.local/problems/" + code));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.toUpperCase(java.util.Locale.ROOT));
        problem.setProperty("correlationId", CorrelationIdFilter.current(request));
        if (fields != null) {
            problem.setProperty("fieldErrors", fields);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return new ResponseEntity<>(problem, headers, status);
    }
}
