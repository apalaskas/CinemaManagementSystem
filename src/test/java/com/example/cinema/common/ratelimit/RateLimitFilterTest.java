package com.example.cinema.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.example.cinema.common.error.ApiProblemFactory;
import com.example.cinema.common.error.ProblemResponseWriter;
import com.example.cinema.user.authentication.CurrentUser;

import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    @Test
    void emitsRetryAfterAndSafeProblemWhenRouteLimitIsExceeded() throws Exception {
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

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/screenings/123/submit");
        request.setRemoteAddr("192.0.2.1");
        return request;
    }
}
