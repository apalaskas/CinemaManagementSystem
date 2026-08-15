package com.example.cinema.program.repository;

import java.util.List;

import com.example.cinema.program.domain.ProgramEntity;

public record ProgramSearchPage(List<ProgramEntity> programs, long totalElements) {

    public ProgramSearchPage {
        programs = List.copyOf(programs);
    }
}
