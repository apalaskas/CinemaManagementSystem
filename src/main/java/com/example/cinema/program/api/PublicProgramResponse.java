package com.example.cinema.program.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PublicProgramResponse(
        UUID programId,
        String name,
        String description,
        LocalDate startDate,
        LocalDate endDate,
        List<String> programmerDisplayNames,
        List<String> finalAuditoriumNames) implements ProgramViewResponse {

    public PublicProgramResponse {
        programmerDisplayNames = List.copyOf(programmerDisplayNames);
        finalAuditoriumNames = List.copyOf(finalAuditoriumNames);
    }
}
