package com.example.cinema.program.api;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProgramCreateRequest(
        @NotBlank(message = "must not be blank")
        @Size(max = 255, message = "must contain at most 255 characters")
        String name,
        @NotBlank(message = "must not be blank")
        String description,
        @NotNull(message = "must be provided")
        LocalDate startDate,
        @NotNull(message = "must be provided")
        LocalDate endDate) {
}
