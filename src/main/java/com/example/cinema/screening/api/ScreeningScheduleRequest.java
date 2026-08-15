package com.example.cinema.screening.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScreeningScheduleRequest(
        @NotBlank
        @Size(max = 255)
        String finalAuditoriumName,
        @NotNull Instant startTime,
        @NotNull Instant endTime) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException(field + " is not accepted");
    }
}
