package com.example.cinema.common.ratelimit;

import java.time.Duration;

public record RateLimitDecision(boolean allowed, Duration retryAfter) {

    public static RateLimitDecision permit() {
        return new RateLimitDecision(true, Duration.ZERO);
    }

    public static RateLimitDecision rejected(Duration retryAfter) {
        return new RateLimitDecision(false, retryAfter);
    }
}
