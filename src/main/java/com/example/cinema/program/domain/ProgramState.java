package com.example.cinema.program.domain;

import java.util.Optional;

public enum ProgramState {
    CREATED,
    SUBMISSION,
    ASSIGNMENT,
    REVIEW,
    SCHEDULING,
    FINAL_PUBLICATION,
    DECISION,
    ANNOUNCED;

    public Optional<ProgramState> next() {
        int nextOrdinal = ordinal() + 1;
        return nextOrdinal < values().length ? Optional.of(values()[nextOrdinal]) : Optional.empty();
    }

    public boolean canTransitionTo(ProgramState requestedState) {
        return next().filter(nextState -> nextState == requestedState).isPresent();
    }
}
