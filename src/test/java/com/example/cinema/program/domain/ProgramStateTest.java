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
        ProgramState[] states = ProgramState.values();
        for (int current = 0; current < states.length; current++) {
            for (int target = 0; target < states.length; target++) {
                assertThat(states[current].canTransitionTo(states[target]))
                        .as("%s -> %s", states[current], states[target])
                        .isEqualTo(target == current + 1);
            }
        }
        assertThat(ProgramState.CREATED.canTransitionTo(null)).isFalse();
        assertThat(ProgramState.ANNOUNCED.next()).isEmpty();
    }
}
