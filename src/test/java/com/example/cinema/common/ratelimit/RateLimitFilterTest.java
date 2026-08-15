package com.example.cinema.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.user.authentication.CurrentUser;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;

import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    @Test
    void appliesScreeningSubmissionPolicyAndEmitsRetryAfterAndSafeProblemWhenExceeded() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, InProcessRateLimiterTest.properties(10, 10));
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.optional()).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(limiter, currentUser,
                new ProblemResponseWriter(new ApiProblemFactory(clock), new ObjectMapper()));

        MockHttpServletRequest first = request();
        filter.doFilter(first, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletRequest second = request();
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(second, rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString())
                .contains("RATE_LIMIT_EXCEEDED")
                .doesNotContain("Exception", "stackTrace", "SQL");
    }

    @Test
    void keysAuthenticatedRequestsByUserIdInsteadOfSharedClientAddress() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, InProcessRateLimiterTest.properties(10, 10));
        CurrentUser currentUser = mock(CurrentUser.class);
        AuthenticatedUserIdentity firstUser = new AuthenticatedUserIdentity(UUID.randomUUID(), "alice", "Alice");
        AuthenticatedUserIdentity secondUser = new AuthenticatedUserIdentity(UUID.randomUUID(), "bob", "Bob");
        when(currentUser.optional()).thenReturn(
                Optional.of(firstUser), Optional.of(secondUser), Optional.of(firstUser));
        RateLimitFilter filter = new RateLimitFilter(limiter, currentUser,
                new ProblemResponseWriter(new ApiProblemFactory(clock), new ObjectMapper()));

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request(), first, new MockFilterChain());
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request(), second, new MockFilterChain());
        MockHttpServletResponse repeated = new MockHttpServletResponse();
        filter.doFilter(request(), repeated, new MockFilterChain());

        assertThat(first.getStatus()).isEqualTo(200);
        assertThat(second.getStatus()).isEqualTo(200);
        assertThat(repeated.getStatus()).isEqualTo(429);
    }

    @Test
    void appliesProgramSearchGroupToAnonymousCollectionGetAndReturns429() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, InProcessRateLimiterTest.properties(1, 10));
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.optional()).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(limiter, currentUser,
                new ProblemResponseWriter(new ApiProblemFactory(clock), new ObjectMapper()));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/programs");
        request.setRemoteAddr("192.0.2.5");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString()).contains("RATE_LIMIT_EXCEEDED", "\"retryable\":true");
    }

    @Test
    void appliesScreeningSearchGroupToProgramCollectionAndReturns429() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, InProcessRateLimiterTest.properties(1, 10));
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.optional()).thenReturn(Optional.empty());
        RateLimitFilter filter = new RateLimitFilter(limiter, currentUser,
                new ProblemResponseWriter(new ApiProblemFactory(clock), new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/programs/cccccccc-cccc-cccc-cccc-cccccccccccc/screenings");
        request.setRemoteAddr("192.0.2.20");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getHeader("Retry-After")).isEqualTo("60");
        assertThat(rejected.getContentAsString())
                .contains("RATE_LIMIT_EXCEEDED", "\"retryable\":true")
                .doesNotContain("SQL", "Exception");
    }

    @Test
    void appliesCreationLimitToProgramScreeningPost() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, InProcessRateLimiterTest.properties(10, 10));
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.optional()).thenReturn(Optional.of(
                new AuthenticatedUserIdentity(UUID.randomUUID(), "alice", "Alice")));
        RateLimitFilter filter = new RateLimitFilter(limiter, currentUser,
                new ProblemResponseWriter(new ApiProblemFactory(clock), new ObjectMapper()));
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/programs/cccccccc-cccc-cccc-cccc-cccccccccccc/screenings");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(request, rejected, new MockFilterChain());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(rejected.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/screenings/123/submit");
        request.setRemoteAddr("192.0.2.1");
        return request;
    }
}
