package com.example.cinema.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.config.CinemaProperties.Idempotency;
import com.example.cinema.common.config.CinemaProperties.Pagination;
import com.example.cinema.common.config.CinemaProperties.Policy;
import com.example.cinema.common.config.CinemaProperties.RateLimit;

class InProcessRateLimiterTest {

    @Test
    void allowsRejectsAndResetsAtTheWindowBoundary() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, properties(2, 10));

        assertThat(limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "address:1").allowed()).isTrue();
        assertThat(limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "address:1").allowed()).isTrue();
        RateLimitDecision rejected = limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "address:1");
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfter()).isEqualTo(Duration.ofMinutes(1));

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "address:1").allowed()).isTrue();
    }

    @Test
    void expiresIdleKeysAndBoundsTheMap() {
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        InProcessRateLimiter limiter = new InProcessRateLimiter(clock, properties(2, 2));
        limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "one");
        limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "two");
        limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "three");
        assertThat(limiter.trackedKeyCount()).isEqualTo(2);

        clock.advance(Duration.ofMinutes(6));
        limiter.tryAcquire(RateLimitGroup.PROGRAM_SEARCH, "fresh");
        assertThat(limiter.trackedKeyCount()).isEqualTo(1);
    }

    static CinemaProperties properties(int searchCapacity, int maxKeys) {
        Policy submit = new Policy(1, Duration.ofMinutes(1));
        Policy creation = new Policy(2, Duration.ofMinutes(1));
        Policy search = new Policy(searchCapacity, Duration.ofMinutes(1));
        return new CinemaProperties(new Pagination(20, 100),
                new RateLimit(submit, creation, search, search, maxKeys, Duration.ofMinutes(5)),
                new Idempotency(Duration.ofHours(24)));
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
