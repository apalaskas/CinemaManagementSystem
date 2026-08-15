package com.example.cinema.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class TimeConfigurationTest {

    @Test
    void providesAUtcClock() {
        assertThat(new TimeConfiguration().clock().getZone()).isEqualTo(ZoneOffset.UTC);
    }
}
