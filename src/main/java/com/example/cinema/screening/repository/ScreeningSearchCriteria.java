package com.example.cinema.screening.repository;

import java.time.Instant;
import java.util.List;

import com.example.cinema.screening.api.ScreeningSearchView;

public record ScreeningSearchCriteria(
        List<String> filmTitleWords,
        List<String> castWords,
        List<String> genreWords,
        Instant fromDateTime,
        Instant toDateTime,
        ScreeningSearchView view) {

    public ScreeningSearchCriteria {
        filmTitleWords = List.copyOf(filmTitleWords);
        castWords = List.copyOf(castWords);
        genreWords = List.copyOf(genreWords);
    }
}
