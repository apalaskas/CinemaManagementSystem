package com.example.cinema.screening.api;

import java.util.UUID;

import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;

public record ScreeningHandlerAssignmentResponse(
        UUID screeningId,
        UserSummaryResponse handler,
        ScreeningState state,
        long version) {
}
