package com.example.cinema.common.error;

import java.io.IOException;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ProblemResponseWriter {

    private final ApiProblemFactory problemFactory;
    private final ObjectMapper objectMapper;

    public ProblemResponseWriter(ApiProblemFactory problemFactory, ObjectMapper objectMapper) {
        this.problemFactory = problemFactory;
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ApplicationException exception)
            throws IOException {
        response.setStatus(exception.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        ProblemDetail problem = problemFactory.create(
                exception.status(), exception.errorCode(), exception.safeDetail(), request, java.util.List.of(),
                exception.retryable());
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
