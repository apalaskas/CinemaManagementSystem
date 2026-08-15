package com.example.cinema.screening.api;

import java.time.Instant;
import java.util.UUID;

import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.screening.domain.ScreeningState;

public record FullScreeningResponse(
        UUID screeningId,
        UUID programId,
        String filmTitle,
        String cast,
        String genre,
        Integer durationMinutes,
        String candidateAuditoriumName,
        String finalAuditoriumName,
        Instant startTime,
        Instant endTime,
        ScreeningState state,
        String conditionalNotes,
        Instant finalSubmittedAt,
        String rejectionReason,
        UserSummaryResponse submitter,
        UserSummaryResponse handler,
        ScreeningReviewDetailResponse review,
        Instant createdAt,
        long version) implements ScreeningViewResponse {
}
