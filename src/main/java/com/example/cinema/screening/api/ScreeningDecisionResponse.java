package com.example.cinema.screening.api;

import java.util.UUID;

import com.example.cinema.screening.domain.ScreeningState;

public record ScreeningDecisionResponse(
        UUID screeningId,
        ScreeningDecision decision,
        ScreeningState state,
        String conditionalNotes,
        String rejectionReason,
        long version) {
}
