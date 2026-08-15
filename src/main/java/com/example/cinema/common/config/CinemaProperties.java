package com.example.cinema.common.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cinema")
public record CinemaProperties(Pagination pagination, RateLimit rateLimit) {

    public CinemaProperties {
        if (pagination == null || rateLimit == null) {
            throw new IllegalArgumentException("pagination and rateLimit configuration are required");
        }
    }

    public record Pagination(int defaultSize, int maxSize) {
        public Pagination {
            if (defaultSize <= 0 || maxSize <= 0 || defaultSize > maxSize) {
                throw new IllegalArgumentException("pagination sizes must be positive and defaultSize <= maxSize");
            }
        }
    }

    public record RateLimit(Policy screeningSubmission, Policy programSearch, Policy screeningSearch) {
        public RateLimit {
            if (screeningSubmission == null || programSearch == null || screeningSearch == null) {
                throw new IllegalArgumentException("all rate-limit policies are required");
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
}
