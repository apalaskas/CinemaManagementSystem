package com.example.cinema.common.infrastructure;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".correlationId";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String incoming = request.getHeader(HEADER_NAME);
        String correlationId = incoming != null && VALID_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (previous == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previous);
            }
        }
    }

    public static String from(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String correlationId ? correlationId : "unavailable";
    }
}
