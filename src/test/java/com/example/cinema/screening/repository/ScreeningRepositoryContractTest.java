package com.example.cinema.screening.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

class ScreeningRepositoryContractTest {

    @Test
    void withdrawalLoadsOnlyAnActiveScreeningWithAPessimisticWriteLock() throws Exception {
        Method method = ScreeningRepository.class.getMethod("findActiveByIdForUpdate", UUID.class);

        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(method.getAnnotation(Query.class).value()).contains("s.deletedAt is null");
    }

    @Test
    void everyActiveWorkflowAndProjectionQueryExcludesSoftDeletedScreenings() {
        Set<String> activeQueryNames = Set.of(
                "findActiveById",
                "findActiveByIdForUpdate",
                "findActiveByProgramId",
                "findActiveOwnedBy",
                "findActiveAssignedTo",
                "countActiveByProgramIdAndState",
                "countActiveSubmittedWithoutFrozenStaffHandler",
                "countActiveReviewCompletionViolations",
                "countActiveDecisionPreparationViolations",
                "countActiveNonFinalDecisionWorkflow",
                "findApprovedWithoutFinalSubmissionForUpdate",
                "findSchedulingConflictsForUpdate",
                "findDistinctScheduledAuditoriums",
                "countActiveAndScheduledByProgramIds");

        assertThat(activeQueryNames).allSatisfy(name -> {
            Method method = java.util.Arrays.stream(ScreeningRepository.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(method.getAnnotation(Query.class))
                    .as("%s declares an explicit active-row query", name)
                    .isNotNull();
            assertThat(method.getAnnotation(Query.class).value())
                    .as("%s excludes soft-deleted rows", name)
                    .contains("deletedAt is null");
        });
    }
}
