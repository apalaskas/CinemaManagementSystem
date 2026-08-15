package com.example.cinema.screening.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;

public record ScreeningReviewResponse(
        UUID reviewId,
        UUID screeningId,
        ScreeningState state,
        BigDecimal numericScore,
        String detailedComments,
        UserSummaryResponse reviewer,
        Instant createdAt,
        long screeningVersion) {
}
