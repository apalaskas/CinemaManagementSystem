package com.example.cinema.program.api;

import java.time.LocalDate;

public record ProgramSearchParameters(
        String name,
        String description,
        LocalDate fromDate,
        LocalDate toDate,
        String filmTitle,
        String auditorium,
        String direction,
        int page,
        Integer size) {
}
