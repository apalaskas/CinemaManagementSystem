package com.example.cinema.screening.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

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
    void screeningDraftUpdateRevalidatesAndWithdrawalIsOneWay() {
        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(), validProgram(), user, "Film", null, null, 90,
                null, Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"), Instant.EPOCH);

        screening.updateDraft(
                " Revised Film ", " Cast ", " Drama ", 100, " Hall ",
                Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"));
        assertThat(screening.getFilmTitle()).isEqualTo("Revised Film");
        assertThat(screening.getCandidateAuditoriumName()).isEqualTo("Hall");

        Instant withdrawnAt = Instant.parse("2027-01-02T00:00:00Z");
        screening.withdraw(withdrawnAt);
        assertThat(screening.getDeletedAt()).isEqualTo(withdrawnAt);
        assertThatIllegalStateException().isThrownBy(() -> screening.withdraw(withdrawnAt));
        assertThatIllegalStateException().isThrownBy(() -> screening.updateDraft(
                "Again", "Cast", "Drama", 100, "Hall", null, null));
    }

    @Test
    void submittedScreeningFreezesRegularDraftUpdatesAndDoesNotSetFinalSubmissionTime() {
        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(), validProgram(), user, "Film", "Cast", "Drama", 90,
                "Hall", Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"), Instant.EPOCH);

        screening.submit();

        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
        assertThat(screening.getFinalSubmittedAt()).isNull();
        assertThatIllegalStateException().isThrownBy(() -> screening.updateDraft(
                "Changed", "Cast", "Drama", 90, "Hall",
                Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z")));
        assertThatIllegalStateException().isThrownBy(screening::submit);
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
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewEntity(
                UUID.randomUUID(), screening, user, new BigDecimal("8.001"), "Detailed review", Instant.EPOCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReviewEntity(
                UUID.randomUUID(), screening, user, new BigDecimal("8.00"),
                "x".repeat(ReviewEntity.MAXIMUM_COMMENT_LENGTH + 1), Instant.EPOCH));
    }

    @Test
    void handlerAssignmentIsSingleAndReviewTransitionRequiresHandledSubmission() {
        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(), validProgram(), user, "Film", "Cast", "Drama", 90,
                "Hall", Instant.parse("2027-02-01T10:00:00Z"),
                Instant.parse("2027-02-01T12:00:00Z"), Instant.EPOCH);
        UserEntity staff = new UserEntity(UUID.randomUUID(), "staff", "hash", "Staff Member");

        assertThatIllegalStateException().isThrownBy(() -> screening.assignHandler(staff));
        screening.submit();
        screening.assignHandler(staff);
        assertThat(screening.getHandler()).isSameAs(staff);
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
        assertThatIllegalStateException().isThrownBy(() -> screening.assignHandler(staff));

        screening.markReviewed();
        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);
        assertThatIllegalStateException().isThrownBy(screening::markReviewed);
    }

    @Test
    void automaticRejectionOnlyAppliesToApprovedScreeningsWithoutFinalSubmission() {
        ScreeningEntity missingFinal = screeningWithState(ScreeningState.APPROVED, null);
        missingFinal.rejectForMissingFinalSubmission("FINAL_SUBMISSION_MISSING");
        assertThat(missingFinal.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(missingFinal.getRejectionReason()).isEqualTo("FINAL_SUBMISSION_MISSING");

        ScreeningEntity finallySubmitted = screeningWithState(
                ScreeningState.APPROVED, Instant.parse("2027-02-01T10:00:00Z"));
        assertThatIllegalStateException().isThrownBy(() ->
                finallySubmitted.rejectForMissingFinalSubmission("FINAL_SUBMISSION_MISSING"));
        assertThat(finallySubmitted.getState()).isEqualTo(ScreeningState.APPROVED);
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

    private ScreeningEntity screeningWithState(ScreeningState state, Instant finalSubmittedAt) {
        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(), validProgram(), user, "Film", "Cast", "Drama", 90,
                "Auditorium", null, null, Instant.EPOCH);
        set(screening, "state", state);
        set(screening, "finalSubmittedAt", finalSubmittedAt);
        return screening;
    }

    private static void set(ScreeningEntity screening, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = ScreeningEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(screening, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
