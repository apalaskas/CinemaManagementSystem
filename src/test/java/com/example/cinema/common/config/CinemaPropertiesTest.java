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
        var source = new MapConfigurationPropertySource(Map.of(
                "cinema.pagination.default-size", "20",
                "cinema.pagination.max-size", "100",
                "cinema.rate-limit.screening-submission.capacity", "10",
                "cinema.rate-limit.screening-submission.refill-period", "1m",
                "cinema.rate-limit.program-search.capacity", "120",
                "cinema.rate-limit.program-search.refill-period", "1m",
                "cinema.rate-limit.screening-search.capacity", "120",
                "cinema.rate-limit.screening-search.refill-period", "1m"));

        CinemaProperties properties = new Binder(source)
                .bind("cinema", Bindable.of(CinemaProperties.class))
                .orElseThrow(() -> new AssertionError("cinema properties did not bind"));

        assertThat(properties.pagination().defaultSize()).isEqualTo(20);
        assertThat(properties.pagination().maxSize()).isEqualTo(100);
        assertThat(properties.rateLimit().screeningSubmission().capacity()).isEqualTo(10);
        assertThat(properties.rateLimit().screeningSubmission().refillPeriod()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void rejectsInvalidPaginationBounds() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new CinemaProperties.Pagination(101, 100));
    }
}
