package com.example.cinema.program.repository;

import java.time.LocalDate;

import com.example.cinema.program.api.ProgramSortDirection;

public record ProgramSearchCriteria(
        String name,
        String description,
        LocalDate fromDate,
        LocalDate toDate,
        String filmTitle,
        String auditorium,
        ProgramSortDirection direction) {
}
