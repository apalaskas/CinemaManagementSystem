package com.example.cinema.screening.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.api.UserSummaryResponse;

public record ScreeningReviewDetailResponse(
        UUID reviewId,
        BigDecimal numericScore,
        String detailedComments,
        UserSummaryResponse reviewer,
        Instant createdAt) {
}
