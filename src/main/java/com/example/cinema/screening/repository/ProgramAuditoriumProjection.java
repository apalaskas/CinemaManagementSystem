package com.example.cinema.screening.repository;

import java.util.UUID;

public interface ProgramAuditoriumProjection {

    UUID getProgramId();

    String getAuditoriumName();
}
