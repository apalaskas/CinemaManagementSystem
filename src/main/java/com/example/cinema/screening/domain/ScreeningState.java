package com.example.cinema.screening.domain;

public enum ScreeningState {
    CREATED(false),
    SUBMITTED(false),
    REVIEWED(false),
    APPROVED(false),
    SCHEDULED(true),
    REJECTED(true);

    private final boolean finalState;

    ScreeningState(boolean finalState) {
        this.finalState = finalState;
    }

    public boolean isFinal() {
        return finalState;
    }
}
