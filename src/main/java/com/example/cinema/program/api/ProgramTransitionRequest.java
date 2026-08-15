package com.example.cinema.program.api;

import com.example.cinema.program.domain.ProgramState;

import jakarta.validation.constraints.NotNull;

public record ProgramTransitionRequest(
        @NotNull(message = "must be provided") ProgramState targetState) {
}
