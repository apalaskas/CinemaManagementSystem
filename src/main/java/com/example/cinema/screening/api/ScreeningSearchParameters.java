package com.example.cinema.screening.api;

import java.time.Instant;

public record ScreeningSearchParameters(
        String filmTitle,
        String cast,
        String genre,
        Instant fromDateTime,
        Instant toDateTime,
        String view,
        int page,
        Integer size) {
}
