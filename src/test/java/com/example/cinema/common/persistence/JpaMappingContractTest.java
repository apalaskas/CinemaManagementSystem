package com.example.cinema.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.example.cinema.audit.domain.AuditLogEntity;
import com.example.cinema.idempotency.IdempotencyRecordEntity;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleId;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.screening.domain.ReviewEntity;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.Column;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.LockModeType;

class JpaMappingContractTest {

    @Test
    void mapsExactlyTheSixConceptualRelations() {
        assertThat(List.of(
                tableName(UserEntity.class),
                tableName(ProgramEntity.class),
                tableName(ProgramRoleEntity.class),
                tableName(ScreeningEntity.class),
                tableName(ReviewEntity.class),
                tableName(AuditLogEntity.class)))
                .containsExactly("cms_user", "program", "program_role", "screening", "review", "audit_log");
    }

    @Test
    void mapsEveryUuidIdentifierToBinarySixteen() throws ReflectiveOperationException {
        assertBinaryUuid(UserEntity.class, "id");
        assertBinaryUuid(ProgramEntity.class, "id");
        assertBinaryUuid(ProgramRoleId.class, "programId");
        assertBinaryUuid(ProgramRoleId.class, "userId");
        assertBinaryUuid(ScreeningEntity.class, "id");
        assertBinaryUuid(ReviewEntity.class, "id");
        assertBinaryUuid(AuditLogEntity.class, "id");
        assertBinaryUuid(AuditLogEntity.class, "targetEntityId");
        assertBinaryUuid(IdempotencyRecordEntity.class, "id");
        assertBinaryUuid(IdempotencyRecordEntity.class, "userId");
    }

    @Test
    void mapsIdempotencyAsInfrastructureRatherThanAConceptualDomainRelation() {
        assertThat(tableName(IdempotencyRecordEntity.class)).isEqualTo("idempotency_record");
        assertThat(List.<Class<?>>of(
                UserEntity.class, ProgramEntity.class, ProgramRoleEntity.class,
                ScreeningEntity.class, ReviewEntity.class, AuditLogEntity.class))
                .doesNotContain(IdempotencyRecordEntity.class);
    }

    @Test
    void usesOptimisticVersionsAndLazyRelationships() throws ReflectiveOperationException {
        assertThat(ProgramEntity.class.getDeclaredField("version").isAnnotationPresent(Version.class)).isTrue();
        assertThat(ScreeningEntity.class.getDeclaredField("version").isAnnotationPresent(Version.class)).isTrue();

        assertLazyManyToOne(ProgramEntity.class, "creator");
        assertLazyManyToOne(ProgramRoleEntity.class, "program");
        assertLazyManyToOne(ProgramRoleEntity.class, "user");
        assertLazyManyToOne(ProgramRoleEntity.class, "assignedBy");
        assertLazyManyToOne(ScreeningEntity.class, "program");
        assertLazyManyToOne(ScreeningEntity.class, "submitter");
        assertLazyManyToOne(ScreeningEntity.class, "handler");
        assertLazyOneToOne(ReviewEntity.class, "screening");
        assertLazyManyToOne(ReviewEntity.class, "staff");
        assertLazyManyToOne(AuditLogEntity.class, "actor");
    }

    @Test
    void schedulingConflictQueryUsesTheIndexedCollationAndHalfOpenIntervals()
            throws ReflectiveOperationException {
        Method method = ScreeningRepository.class.getMethod(
                "findSchedulingConflictsForUpdate",
                UUID.class,
                String.class,
                Instant.class,
                Instant.class);

        String query = method.getAnnotation(Query.class).value();
        assertThat(query)
                .contains("s.state = com.example.cinema.screening.domain.ScreeningState.SCHEDULED")
                .contains("s.deletedAt is null")
                .contains("s.finalAuditoriumName = :finalAuditoriumName")
                .contains("s.id <> :excludedScreeningId")
                .contains("s.startTime < :requestedEnd")
                .contains("s.endTime > :requestedStart")
                .doesNotContain("lower(")
                .doesNotContain("s.program.id");
        assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void lifecycleQueriesLockTheProgramAndOnlyMissingFinalSubmissions() throws ReflectiveOperationException {
        Method programLock = ProgramRepository.class.getMethod("findByIdForUpdate", UUID.class);
        assertThat(programLock.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);

        Method screeningLock = ScreeningRepository.class.getMethod(
                "findApprovedWithoutFinalSubmissionForUpdate", UUID.class);
        assertThat(screeningLock.getAnnotation(Lock.class).value())
                .isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        assertThat(screeningLock.getAnnotation(Query.class).value())
                .contains("ScreeningState.APPROVED")
                .contains("s.finalSubmittedAt is null")
                .contains("s.deletedAt is null")
                .contains("order by s.id");
    }

    @Test
    void lifecycleGuardQueriesCoverTheCompleteActiveWorkflowSet() throws ReflectiveOperationException {
        String handlerGuard = ScreeningRepository.class
                .getMethod("countActiveSubmittedWithoutFrozenStaffHandler", UUID.class)
                .getAnnotation(Query.class).value();
        assertThat(handlerGuard)
                .contains("s.deletedAt is null")
                .contains("ScreeningState.SUBMITTED")
                .contains("s.handler is null")
                .contains("ProgramRoleType.STAFF")
                .contains("r.id.userId = s.handler.id");

        String reviewGuard = ScreeningRepository.class
                .getMethod("countActiveReviewCompletionViolations", UUID.class)
                .getAnnotation(Query.class).value();
        assertThat(reviewGuard)
                .contains("s.deletedAt is null")
                .contains("s.state <> com.example.cinema.screening.domain.ScreeningState.CREATED")
                .contains("s.state <> com.example.cinema.screening.domain.ScreeningState.REVIEWED")
                .contains("not exists")
                .contains("ReviewEntity");

        String decisionPreparationGuard = ScreeningRepository.class
                .getMethod("countActiveDecisionPreparationViolations", UUID.class)
                .getAnnotation(Query.class).value();
        assertThat(decisionPreparationGuard)
                .contains("s.deletedAt is null")
                .contains("s.state not in")
                .contains("ScreeningState.CREATED")
                .contains("ScreeningState.APPROVED")
                .contains("ScreeningState.REJECTED");

        String announcementGuard = ScreeningRepository.class
                .getMethod("countActiveNonFinalDecisionWorkflow", UUID.class)
                .getAnnotation(Query.class).value();
        assertThat(announcementGuard)
                .contains("s.deletedAt is null")
                .contains("ScreeningState.SUBMITTED")
                .contains("ScreeningState.REVIEWED")
                .contains("ScreeningState.APPROVED")
                .doesNotContain("ScreeningState.SCHEDULED")
                .doesNotContain("ScreeningState.REJECTED");
    }

    private static String tableName(Class<?> entityType) {
        return entityType.getAnnotation(Table.class).name();
    }

    private static void assertBinaryUuid(Class<?> type, String fieldName) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        assertThat(field.getType()).isEqualTo(UUID.class);
        assertThat(field.getAnnotation(JdbcTypeCode.class).value()).isEqualTo(SqlTypes.BINARY);
        assertThat(field.getAnnotation(Column.class).columnDefinition()).isEqualTo("binary(16)");
    }

    private static void assertLazyManyToOne(Class<?> type, String fieldName) throws ReflectiveOperationException {
        assertThat(type.getDeclaredField(fieldName).getAnnotation(ManyToOne.class).fetch())
                .isEqualTo(FetchType.LAZY);
    }

    private static void assertLazyOneToOne(Class<?> type, String fieldName) throws ReflectiveOperationException {
        assertThat(type.getDeclaredField(fieldName).getAnnotation(OneToOne.class).fetch())
                .isEqualTo(FetchType.LAZY);
    }
}
