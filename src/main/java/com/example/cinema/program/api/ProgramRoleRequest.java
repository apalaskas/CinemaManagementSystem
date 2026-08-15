package com.example.cinema.program.api;

import java.util.UUID;

import com.example.cinema.program.domain.ProgramRoleType;

import jakarta.validation.constraints.NotNull;

public record ProgramRoleRequest(
        @NotNull(message = "must be provided") UUID userId,
        @NotNull(message = "must be provided") ProgramRoleType role) {
}
