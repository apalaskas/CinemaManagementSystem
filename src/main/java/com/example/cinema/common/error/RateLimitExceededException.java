package com.example.cinema.common.error;

import java.time.Duration;

import org.springframework.http.HttpStatus;

public class RateLimitExceededException extends ApplicationException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", "The request rate limit was exceeded.", true);
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
