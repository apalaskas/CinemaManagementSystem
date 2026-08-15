package com.example.cinema.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cinema")
public record CinemaProperties(Pagination pagination, RateLimit rateLimit, Idempotency idempotency) {

    public CinemaProperties {
        if (pagination == null || rateLimit == null || idempotency == null) {
            throw new IllegalArgumentException("pagination, rateLimit, and idempotency configuration are required");
        }
    }

    public record Pagination(int defaultSize, int maxSize) {
        public Pagination {
            if (defaultSize <= 0 || maxSize <= 0 || defaultSize > maxSize) {
                throw new IllegalArgumentException("pagination sizes must be positive and defaultSize <= maxSize");
            }
        }
    }

    public record RateLimit(
            Policy screeningSubmission,
            Policy creation,
            Policy programSearch,
            Policy screeningSearch,
            int maxTrackedKeys,
            Duration entryTtl) {
        public RateLimit {
            if (screeningSubmission == null || creation == null || programSearch == null || screeningSearch == null) {
                throw new IllegalArgumentException("all rate-limit policies are required");
            }
            if (maxTrackedKeys <= 0 || entryTtl == null || entryTtl.isZero() || entryTtl.isNegative()) {
                throw new IllegalArgumentException("rate-limit map bounds and entryTtl must be positive");
            }
        }
    }

    public record Policy(int capacity, Duration refillPeriod) {
        public Policy {
            if (capacity <= 0 || refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
                throw new IllegalArgumentException("rate-limit capacity and refillPeriod must be positive");
            }
        }
    }

    public record Idempotency(Duration retention) {
        public Idempotency {
            if (retention == null || retention.isZero() || retention.isNegative()) {
                throw new IllegalArgumentException("idempotency retention must be positive");
            }
        }
    }
}
