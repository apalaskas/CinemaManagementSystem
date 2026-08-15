package com.example.cinema.screening.api;

import java.util.UUID;

public sealed interface ScreeningViewResponse permits PublicScreeningResponse, FullScreeningResponse {

    UUID screeningId();

    String filmTitle();

    String genre();
}
