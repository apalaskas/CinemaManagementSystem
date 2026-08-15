package com.example.cinema.common.ratelimit;

import java.io.IOException;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.common.error.RateLimitExceededException;
import com.example.cinema.user.authentication.CurrentUser;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern PROGRAM_SCREENINGS = Pattern.compile("/api/v1/programs/[^/]+/screenings");
    private static final Pattern SCREENING_SUBMIT = Pattern.compile("/api/v1/screenings/[^/]+/submit");

    private final InProcessRateLimiter rateLimiter;
    private final CurrentUser currentUser;
    private final ProblemResponseWriter problemWriter;

    public RateLimitFilter(
            InProcessRateLimiter rateLimiter,
            CurrentUser currentUser,
            ProblemResponseWriter problemWriter) {
        this.rateLimiter = rateLimiter;
        this.currentUser = currentUser;
        this.problemWriter = problemWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Optional<RateLimitGroup> group = routeGroup(request.getMethod(), request.getRequestURI());
        if (group.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        String subject = currentUser.optional()
                .map(identity -> "user:" + identity.userId())
                .orElseGet(() -> "address:" + safeClientAddress(request));
        RateLimitDecision decision = rateLimiter.tryAcquire(group.orElseThrow(), subject);
        if (decision.allowed()) {
            filterChain.doFilter(request, response);
            return;
        }
        RateLimitExceededException exception = new RateLimitExceededException(decision.retryAfter());
        response.setHeader(HttpHeaders.RETRY_AFTER,
                Long.toString(Math.max(1, (decision.retryAfter().toMillis() + 999) / 1000)));
        problemWriter.write(request, response, exception);
    }

    private static Optional<RateLimitGroup> routeGroup(String method, String uri) {
        if ("GET".equals(method) && "/api/v1/programs".equals(uri)) {
            return Optional.of(RateLimitGroup.PROGRAM_SEARCH);
        }
        if ("GET".equals(method) && PROGRAM_SCREENINGS.matcher(uri).matches()) {
            return Optional.of(RateLimitGroup.SCREENING_SEARCH);
        }
        if ("POST".equals(method)
                && ("/api/v1/programs".equals(uri) || PROGRAM_SCREENINGS.matcher(uri).matches())) {
            return Optional.of(RateLimitGroup.CREATION);
        }
        if ("POST".equals(method) && SCREENING_SUBMIT.matcher(uri).matches()) {
            return Optional.of(RateLimitGroup.SCREENING_SUBMISSION);
        }
        return Optional.empty();
    }

    private static String safeClientAddress(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return address == null || address.isBlank() ? "unknown" : address;
    }
}
