package com.example.cinema.program.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramState;

public record ProgramDetailResponse(
        UUID programId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        ProgramState state,
        Instant createdAt,
        long version,
        UserSummaryResponse creator) {
}
