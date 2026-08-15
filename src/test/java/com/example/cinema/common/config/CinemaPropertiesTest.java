package com.example.cinema.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CinemaPropertiesTest {

    @Test
    void bindsPaginationAndRateLimitConfigurationWithoutStartingSpringOrMySql() {
        var source = new MapConfigurationPropertySource(Map.ofEntries(
                Map.entry("cinema.pagination.default-size", "20"),
                Map.entry("cinema.pagination.max-size", "100"),
                Map.entry("cinema.rate-limit.screening-submission.capacity", "10"),
                Map.entry("cinema.rate-limit.screening-submission.refill-period", "1m"),
                Map.entry("cinema.rate-limit.creation.capacity", "30"),
                Map.entry("cinema.rate-limit.creation.refill-period", "1m"),
                Map.entry("cinema.rate-limit.program-search.capacity", "120"),
                Map.entry("cinema.rate-limit.program-search.refill-period", "1m"),
                Map.entry("cinema.rate-limit.screening-search.capacity", "120"),
                Map.entry("cinema.rate-limit.screening-search.refill-period", "1m"),
                Map.entry("cinema.rate-limit.max-tracked-keys", "10000"),
                Map.entry("cinema.rate-limit.entry-ttl", "15m"),
                Map.entry("cinema.idempotency.retention", "24h")));

        CinemaProperties properties = new Binder(source)
                .bind("cinema", Bindable.of(CinemaProperties.class))
                .orElseThrow(() -> new AssertionError("cinema properties did not bind"));

        assertThat(properties.pagination().defaultSize()).isEqualTo(20);
        assertThat(properties.pagination().maxSize()).isEqualTo(100);
        assertThat(properties.rateLimit().screeningSubmission().capacity()).isEqualTo(10);
        assertThat(properties.rateLimit().screeningSubmission().refillPeriod()).isEqualTo(Duration.ofMinutes(1));
        assertThat(properties.rateLimit().creation().capacity()).isEqualTo(30);
        assertThat(properties.rateLimit().maxTrackedKeys()).isEqualTo(10_000);
        assertThat(properties.idempotency().retention()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void rejectsInvalidPaginationBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CinemaProperties.Pagination(101, 100));
    }
}
