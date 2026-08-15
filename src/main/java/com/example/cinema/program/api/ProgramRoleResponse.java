package com.example.cinema.program.api;

import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramRoleType;

public record ProgramRoleResponse(
        UUID programId,
        UUID userId,
        String fullName,
        ProgramRoleType role,
        Instant assignedAt,
        UUID assignedByUserId,
        long programVersion) {
}
