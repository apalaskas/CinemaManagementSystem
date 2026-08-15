package com.example.cinema.screening.api;

import java.math.BigDecimal;

import com.example.cinema.screening.domain.ReviewEntity;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScreeningReviewRequest(
        @NotNull
        @DecimalMin("0.00")
        @DecimalMax("10.00")
        @Digits(integer = 2, fraction = 2)
        BigDecimal numericScore,

        @NotBlank
        @Size(max = ReviewEntity.MAXIMUM_COMMENT_LENGTH)
        String detailedComments) {
}
