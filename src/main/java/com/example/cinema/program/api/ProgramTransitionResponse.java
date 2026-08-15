package com.example.cinema.program.api;

import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.domain.ProgramState;

public record ProgramTransitionResponse(
        UUID programId,
        ProgramState oldState,
        ProgramState newState,
        long version,
        Instant transitionedAt,
        int automaticallyRejectedScreenings) {
}
