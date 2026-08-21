package com.example.cinema.screening.api;

import java.util.UUID;

public sealed interface ScreeningViewResponse permits PublicScreeningResponse, FullScreeningResponse {

    UUID screeningId();

    UUID programId();

    String filmTitle();

    String genre();
}
