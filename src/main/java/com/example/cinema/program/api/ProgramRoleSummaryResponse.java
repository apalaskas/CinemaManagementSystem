package com.example.cinema.program.api;

import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramRoleType;

public record ProgramRoleSummaryResponse(
        UUID userId,
        String username,
        String fullName,
        ProgramRoleType role,
        Instant assignedAt,
        UUID assignedByUserId) {
}
