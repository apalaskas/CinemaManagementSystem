package com.example.cinema.screening.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

public record ScreeningDecisionRequest(
        @NotNull ScreeningDecision decision,
        String conditionalNotes,
        String reason) {

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        throw new IllegalArgumentException(field + " is not accepted");
    }
}
