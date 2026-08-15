package com.example.cinema.common.error;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import com.example.cinema.common.infrastructure.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

@Component
public class ApiProblemFactory {

    private final Clock clock;

    public ApiProblemFactory(Clock clock) {
        this.clock = clock;
    }

    public ProblemDetail create(
            HttpStatus status,
            String errorCode,
            String detail,
            HttpServletRequest request) {
        return create(status, errorCode, detail, request, List.of(), false);
    }

    public ProblemDetail create(
            HttpStatus status,
            String errorCode,
            String detail,
            HttpServletRequest request,
            List<FieldErrorDetail> fieldErrors,
            boolean retryable) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("errorCode", errorCode);
        problem.setProperty("timestamp", Instant.now(clock));
        problem.setProperty("traceId", CorrelationIdFilter.from(request));
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        if (retryable) {
            problem.setProperty("retryable", true);
        }
        return problem;
    }

    public record FieldErrorDetail(String field, String message) {
    }
}
