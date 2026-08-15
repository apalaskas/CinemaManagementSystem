package com.example.cinema.program.api;

public record ProgramScreeningSummaryResponse(
        long activeScreeningCount,
        long scheduledScreeningCount,
        String collectionUrl) {
}
