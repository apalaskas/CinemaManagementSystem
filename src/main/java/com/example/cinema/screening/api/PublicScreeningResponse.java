package com.example.cinema.screening.api;

import java.time.Instant;
import java.util.UUID;

public record PublicScreeningResponse(
        UUID screeningId,
        String filmTitle,
        String genre,
        Instant startTime,
        Instant endTime,
        String finalAuditoriumName) implements ScreeningViewResponse {
}
