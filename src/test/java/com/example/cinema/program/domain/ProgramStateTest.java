package com.example.cinema.program.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProgramStateTest {

    @Test
    void definesOnlyTheCanonicalLifecycleInOrder() {
        assertThat(ProgramState.values()).containsExactly(
                ProgramState.CREATED,
                ProgramState.SUBMISSION,
                ProgramState.ASSIGNMENT,
                ProgramState.REVIEW,
                ProgramState.SCHEDULING,
                ProgramState.FINAL_PUBLICATION,
                ProgramState.DECISION,
                ProgramState.ANNOUNCED);
    }

    @Test
    void permitsOnlyTheImmediateNextState() {
        assertThat(ProgramState.CREATED.canTransitionTo(ProgramState.SUBMISSION)).isTrue();
        assertThat(ProgramState.CREATED.canTransitionTo(ProgramState.ASSIGNMENT)).isFalse();
        assertThat(ProgramState.ANNOUNCED.next()).isEmpty();
    }
}
