package com.example.cinema.screening.api;

import java.time.Instant;
import java.util.UUID;

import com.example.cinema.screening.domain.ScreeningState;

public record ScreeningScheduleResponse(
        UUID screeningId,
        ScreeningState state,
        String finalAuditoriumName,
        Instant startTime,
        Instant endTime,
        long version) {
}
