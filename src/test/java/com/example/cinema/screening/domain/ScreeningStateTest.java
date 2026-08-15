package com.example.cinema.screening.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ScreeningStateTest {

    @Test
    void definesOnlyCanonicalStates() {
        assertThat(ScreeningState.values()).containsExactly(
                ScreeningState.CREATED,
                ScreeningState.SUBMITTED,
                ScreeningState.REVIEWED,
                ScreeningState.APPROVED,
                ScreeningState.SCHEDULED,
                ScreeningState.REJECTED);
    }

    @Test
    void marksOnlyScheduledAndRejectedAsFinal() {
        assertThat(ScreeningState.values())
                .filteredOn(ScreeningState::isFinal)
                .containsExactly(ScreeningState.SCHEDULED, ScreeningState.REJECTED);
    }
}
