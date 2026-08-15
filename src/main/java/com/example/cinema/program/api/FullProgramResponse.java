package com.example.cinema.program.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramState;

public record FullProgramResponse(
        UUID programId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        List<String> programmerDisplayNames,
        List<String> finalAuditoriumNames,
        ProgramState state,
        Instant createdAt,
        long version,
        UserSummaryResponse creator,
        List<ProgramRoleSummaryResponse> roles,
        ProgramScreeningSummaryResponse screenings) implements ProgramViewResponse {

    public FullProgramResponse {
        programmerDisplayNames = List.copyOf(programmerDisplayNames);
        finalAuditoriumNames = List.copyOf(finalAuditoriumNames);
        roles = List.copyOf(roles);
    }
}
