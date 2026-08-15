package com.example.cinema.screening.repository;

import java.util.UUID;

public interface ProgramScreeningCountProjection {

    UUID getProgramId();

    long getActiveCount();

    long getScheduledCount();
}
