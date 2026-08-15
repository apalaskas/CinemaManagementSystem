package com.example.cinema.program.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProgramRoleIdTest {

    @Test
    void equalityUsesBothProgramAndUserIdentifiers() {
        UUID programId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();

        ProgramRoleId first = new ProgramRoleId(programId, userId);
        ProgramRoleId same = new ProgramRoleId(programId, userId);
        ProgramRoleId differentUser = new ProgramRoleId(programId, UUID.randomUUID());

        assertThat(first)
                .isEqualTo(same)
                .hasSameHashCodeAs(same)
                .isNotEqualTo(differentUser);
    }
}
