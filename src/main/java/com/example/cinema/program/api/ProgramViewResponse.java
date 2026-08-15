package com.example.cinema.program.api;

import java.time.LocalDate;
import java.util.UUID;

public sealed interface ProgramViewResponse permits PublicProgramResponse, FullProgramResponse {

    UUID programId();

    String name();

    String description();

    LocalDate startDate();

    LocalDate endDate();
}
