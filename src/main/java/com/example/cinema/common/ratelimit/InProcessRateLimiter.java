package com.example.cinema.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.config.CinemaProperties.Policy;

@Component
public class InProcessRateLimiter {

    private final Clock clock;
    private final CinemaProperties.RateLimit configuration;
    private final Map<BucketKey, Window> windows = new HashMap<>();

    public InProcessRateLimiter(Clock clock, CinemaProperties properties) {
        this.clock = clock;
        this.configuration = properties.rateLimit();
    }

    public synchronized RateLimitDecision tryAcquire(RateLimitGroup group, String subjectKey) {
        Instant now = clock.instant();
        removeExpired(now);
        BucketKey key = new BucketKey(group, subjectKey);
        Policy policy = policy(group);
        Window window = windows.get(key);
        if (window == null || !now.isBefore(window.startedAt().plus(policy.refillPeriod()))) {
            ensureCapacity();
            windows.put(key, new Window(now, now, 1));
            return RateLimitDecision.permit();
        }
        if (window.count() >= policy.capacity()) {
            windows.put(key, new Window(window.startedAt(), now, window.count()));
            return RateLimitDecision.rejected(Duration.between(now, window.startedAt().plus(policy.refillPeriod())));
        }
        windows.put(key, new Window(window.startedAt(), now, window.count() + 1));
        return RateLimitDecision.permit();
    }

    synchronized int trackedKeyCount() {
        return windows.size();
    }

    private void removeExpired(Instant now) {
        windows.entrySet().removeIf(entry ->
                !now.isBefore(entry.getValue().lastSeen().plus(configuration.entryTtl())));
    }

    private void ensureCapacity() {
        if (windows.size() < configuration.maxTrackedKeys()) {
            return;
        }
        windows.entrySet().stream()
                .min(Comparator.comparing(entry -> entry.getValue().lastSeen()))
                .map(Map.Entry::getKey)
                .ifPresent(windows::remove);
    }

    private Policy policy(RateLimitGroup group) {
        return switch (group) {
            case PROGRAM_SEARCH -> configuration.programSearch();
            case SCREENING_SEARCH -> configuration.screeningSearch();
            case CREATION -> configuration.creation();
            case SCREENING_SUBMISSION -> configuration.screeningSubmission();
        };
    }

    private record BucketKey(RateLimitGroup group, String subjectKey) {
    }

    private record Window(Instant startedAt, Instant lastSeen, int count) {
    }
}
