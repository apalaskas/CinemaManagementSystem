package com.example.cinema.screening.repository;

import java.util.List;

import com.example.cinema.screening.domain.ScreeningEntity;

public record ScreeningSearchPage(List<ScreeningEntity> screenings, long totalElements) {

    public ScreeningSearchPage {
        screenings = List.copyOf(screenings);
    }
}
