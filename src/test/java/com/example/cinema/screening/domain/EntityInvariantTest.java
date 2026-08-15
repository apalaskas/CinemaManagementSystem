package com.example.cinema.screening.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.user.domain.UserEntity;

class EntityInvariantTest {

    private final UserEntity user = new UserEntity(
            UUID.randomUUID(), "user", "$2a$10$test-hash", "Test User");

    @Test
    void programRejectsAnInvertedDateRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ProgramEntity(
                UUID.randomUUID(),
                user,
                "Program",
                "Description",
                LocalDate.of(2027, 2, 2),
                LocalDate.of(2027, 2, 1),
                Instant.parse("2027-01-01T00:00:00Z")))
                .withMessageContaining("endDate");
    }

    @Test
    void screeningAllowsAPartialCreatedDraft() {
        ScreeningEntity draft = new ScreeningEntity(
                UUID.randomUUID(),
                validProgram(),
                user,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                Instant.parse("2027-01-01T00:00:00Z"));

        assertThat(draft.getState()).isEqualTo(ScreeningState.CREATED);
        assertThat(draft.getFilmTitle()).isNull();
    }

    @Test
    void screeningRejectsAnIntervalShorterThanTheFilm() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ScreeningEntity(
                UUID.randomUUID(),
                validProgram(),
                user,
                "Film",
                "Cast",
                "Drama",
                120,
                "Auditorium A",
                Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T11:59:59Z"),
                Instant.parse("2027-01-01T00:00:00Z")))
                .withMessageContaining("durationMinutes");
    }

    @Test
    void reviewEnforcesInclusiveScoreRangeAndNonblankComments() {
        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(), validProgram(), user, null, null, null, null, null, null, null, Instant.EPOCH);

        ReviewEntity review = new ReviewEntity(
                UUID.randomUUID(), screening, user, new BigDecimal("10.00"), "Detailed review", Instant.EPOCH);
        assertThat(review.getNumericScore()).isEqualByComparingTo("10.00");

        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewEntity(
                UUID.randomUUID(), screening, user, new BigDecimal("10.01"), "Detailed review", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewEntity(
                UUID.randomUUID(), screening, user, new BigDecimal("8.00"), "  ", Instant.EPOCH));
    }

    private ProgramEntity validProgram() {
        return new ProgramEntity(
                UUID.randomUUID(),
                user,
                "Program",
                "Description",
                LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31),
                Instant.parse("2027-01-01T00:00:00Z"));
    }
}
