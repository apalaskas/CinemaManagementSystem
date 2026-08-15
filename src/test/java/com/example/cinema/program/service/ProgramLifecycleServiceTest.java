package com.example.cinema.program.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ProgramTransitionPrerequisiteException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.api.ProgramTransitionRequest;
import com.example.cinema.program.api.ProgramTransitionResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;

import tools.jackson.databind.ObjectMapper;

class ProgramLifecycleServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SCREENING_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID SECOND_SCREENING_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

    private final ProgramRepository programRepository = org.mockito.Mockito.mock(ProgramRepository.class);
    private final ProgramRoleRepository roleRepository = org.mockito.Mockito.mock(ProgramRoleRepository.class);
    private final ScreeningRepository screeningRepository = org.mockito.Mockito.mock(ScreeningRepository.class);
    private final ContextAwareAuthorizationService authorization =
            org.mockito.Mockito.mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = org.mockito.Mockito.mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = org.mockito.Mockito.mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = org.mockito.Mockito.mock(ObjectMapper.class);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity actor = new UserEntity(ACTOR_ID, "alice", "credential", "Alice Programmer");
    private final ProgramLifecycleService service = new ProgramLifecycleService(
            programRepository,
            roleRepository,
            screeningRepository,
            authorization,
            idempotencyManager,
            auditLoggingService,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(authorization.currentUser()).thenReturn(
                new AuthenticatedUserIdentity(ACTOR_ID, "alice", "Alice Programmer"));
        when(roleRepository.existsByProgramIdAndRole(PROGRAM_ID, ProgramRoleType.STAFF)).thenReturn(true);
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID)).thenReturn(List.of());
        when(programRepository.saveAndFlush(any(ProgramEntity.class))).thenAnswer(invocation -> {
            ProgramEntity program = invocation.getArgument(0);
            set(program, "version", program.getVersion() + 1);
            return program;
        });
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            encodedResponse.set(invocation.getArgument(0));
            return "{\"stored\":true}";
        });
        when(objectMapper.readValue(anyString(), any(Class.class))).thenAnswer(invocation ->
                invocation.<Class<Object>>getArgument(1).cast(encodedResponse.get()));
        when(idempotencyManager.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResponse> command = invocation.getArgument(3);
            StoredCommandResponse response = command.get();
            return new IdempotencyResult(response.status(), response.body(), false);
        });
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("legalTransitions")
    void performsAllSevenExactForwardTransitions(ProgramState oldState, ProgramState targetState) {
        ProgramEntity program = program(oldState, 4);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));

        ProgramCommandResult<ProgramTransitionResponse> result = transition(targetState, 4, "key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body()).isEqualTo(new ProgramTransitionResponse(
                PROGRAM_ID, oldState, targetState, 5, NOW, 0));
        assertThat(program.getState()).isEqualTo(targetState);
        verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        verify(authorization, times(2)).requireProgrammer(PROGRAM_ID);
        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_STATE_TRANSITIONED"), eq("PROGRAM"), eq(PROGRAM_ID),
                any(), any(), eq(null));
    }

    @ParameterizedTest(name = "reject {0} -> {1}")
    @MethodSource("illegalTransitions")
    void rejectsEveryReverseSkippedRepeatedAndTerminalTransition(
            ProgramState oldState,
            ProgramState targetState) {
        ProgramEntity program = program(oldState, 2);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));

        assertThatThrownBy(() -> transition(targetState, 2, "invalid-key"))
                .isInstanceOf(InvalidStateException.class);
        assertThat(program.getState()).isEqualTo(oldState);
        verify(programRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checksProgrammerAuthorizationBeforeIdempotencyAndAgainAfterLock() {
        doThrow(new ForbiddenException()).when(authorization).requireProgrammer(PROGRAM_ID);

        assertThatThrownBy(() -> transition(ProgramState.SUBMISSION, 0, "key"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
        verify(programRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void concealsUnknownProgramAndDoesNotAudit() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transition(ProgramState.SUBMISSION, 0, "key"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingOrNullTargetAtTheServiceBoundary() {
        assertThatThrownBy(() -> service.transition(PROGRAM_ID, 0, null, "key"))
                .isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> service.transition(
                PROGRAM_ID, 0, new ProgramTransitionRequest(null), "key"))
                .isInstanceOf(InvalidInputException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @Test
    void requiresStaffBeforeSubmission() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.CREATED, 0)));
        when(roleRepository.existsByProgramIdAndRole(PROGRAM_ID, ProgramRoleType.STAFF)).thenReturn(false);

        assertPrerequisiteFailure(ProgramState.SUBMISSION);
    }

    @Test
    void requiresEverySubmittedScreeningToHaveAFrozenStaffHandlerBeforeReview() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.ASSIGNMENT, 0)));
        when(screeningRepository.countActiveSubmittedWithoutFrozenStaffHandler(PROGRAM_ID)).thenReturn(1L);

        assertPrerequisiteFailure(ProgramState.REVIEW);
    }

    @Test
    void requiresCompletedReviewsBeforeScheduling() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.REVIEW, 0)));
        when(screeningRepository.countActiveReviewCompletionViolations(PROGRAM_ID)).thenReturn(1L);

        assertPrerequisiteFailure(ProgramState.SCHEDULING);
    }

    @Test
    void requiresEveryReviewedScreeningToBeDecidedBeforeFinalPublication() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.SCHEDULING, 0)));
        when(screeningRepository.countActiveDecisionPreparationViolations(PROGRAM_ID)).thenReturn(1L);

        assertPrerequisiteFailure(ProgramState.FINAL_PUBLICATION);
    }

    @Test
    void requiresFinalScreeningStatesBeforeAnnouncement() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.DECISION, 0)));
        when(screeningRepository.countActiveNonFinalDecisionWorkflow(PROGRAM_ID)).thenReturn(1L);

        assertPrerequisiteFailure(ProgramState.ANNOUNCED);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void automaticallyRejectsEveryApprovedScreeningMissingFinalSubmissionAndSystemAuditsEach() {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION, 3);
        ScreeningEntity screening = screening(ScreeningState.APPROVED, null, 7);
        ScreeningEntity secondScreening = screening(SECOND_SCREENING_ID, ScreeningState.APPROVED, null, 11);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID))
                .thenReturn(List.of(screening, secondScreening));
        when(screeningRepository.saveAllAndFlush(any())).thenAnswer(invocation -> {
            set(screening, "version", 8L);
            set(secondScreening, "version", 12L);
            return invocation.getArgument(0);
        });

        ProgramCommandResult<ProgramTransitionResponse> result =
                transition(ProgramState.DECISION, 3, "decision-key");

        assertThat(result.body().automaticallyRejectedScreenings()).isEqualTo(2);
        assertThat(screening.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(screening.getRejectionReason()).isEqualTo("FINAL_SUBMISSION_MISSING");
        assertThat(secondScreening.getState()).isEqualTo(ScreeningState.REJECTED);
        assertThat(secondScreening.getRejectionReason()).isEqualTo("FINAL_SUBMISSION_MISSING");
        verify(screeningRepository).saveAllAndFlush(List.of(screening, secondScreening));
        org.mockito.ArgumentCaptor<Map> oldSnapshot = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<Map> newSnapshot = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordSystemAction(
                eq("SCREENING_AUTOMATICALLY_REJECTED"), eq("SCREENING"), eq(SCREENING_ID),
                oldSnapshot.capture(), newSnapshot.capture(), eq("FINAL_SUBMISSION_MISSING"));
        assertThat(oldSnapshot.getValue()).containsEntry("state", ScreeningState.APPROVED);
        assertThat(newSnapshot.getValue())
                .containsEntry("state", ScreeningState.REJECTED)
                .containsEntry("rejectionReason", "FINAL_SUBMISSION_MISSING");
        verify(auditLoggingService).recordSystemAction(
                eq("SCREENING_AUTOMATICALLY_REJECTED"), eq("SCREENING"), eq(SECOND_SCREENING_ID),
                any(), any(), eq("FINAL_SUBMISSION_MISSING"));
    }

    @Test
    @SuppressWarnings("rawtypes")
    void recordsActorOldStateNewStateVersionAndTimestampInOneProgramAudit() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.SUBMISSION, 6)));

        transition(ProgramState.ASSIGNMENT, 6, "audit-key");

        org.mockito.ArgumentCaptor<Map> oldSnapshot = org.mockito.ArgumentCaptor.forClass(Map.class);
        org.mockito.ArgumentCaptor<Map> newSnapshot = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_STATE_TRANSITIONED"), eq("PROGRAM"), eq(PROGRAM_ID),
                oldSnapshot.capture(), newSnapshot.capture(), eq(null));
        assertThat(oldSnapshot.getValue())
                .containsEntry("state", ProgramState.SUBMISSION)
                .containsEntry("version", 6L)
                .containsEntry("transitionedAt", null);
        assertThat(newSnapshot.getValue())
                .containsEntry("state", ProgramState.ASSIGNMENT)
                .containsEntry("version", 7L)
                .containsEntry("transitionedAt", NOW);
        verify(auditLoggingService, never()).recordSystemAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotAutomaticallyRejectFinallySubmittedApprovedScreenings() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.FINAL_PUBLICATION, 1)));
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID))
                .thenReturn(List.of());

        ProgramCommandResult<ProgramTransitionResponse> result =
                transition(ProgramState.DECISION, 1, "decision-key");

        assertThat(result.body().automaticallyRejectedScreenings()).isZero();
        verify(screeningRepository, never()).saveAllAndFlush(any());
        verify(auditLoggingService, never()).recordSystemAction(any(), any(), any(), any(), any(), any());
    }

    @Test
    void screeningWriteFailurePreventsProgramUpdateAndAllAudits() {
        ScreeningEntity screening = screening(ScreeningState.APPROVED, null, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.FINAL_PUBLICATION, 0)));
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID))
                .thenReturn(List.of(screening));
        when(screeningRepository.saveAllAndFlush(any())).thenThrow(new IllegalStateException("screening write failed"));

        assertThatThrownBy(() -> transition(ProgramState.DECISION, 0, "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("screening write failed");
        verify(programRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordSystemAction(any(), any(), any(), any(), any(), any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void programWriteFailureAfterAutomaticRejectionCannotProduceAStoredSuccess() throws Exception {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION, 4);
        ScreeningEntity screening = screening(ScreeningState.APPROVED, null, 2);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID))
                .thenReturn(List.of(screening));
        doThrow(new ObjectOptimisticLockingFailureException(ProgramEntity.class, PROGRAM_ID))
                .when(programRepository).saveAndFlush(program);

        assertThatThrownBy(() -> transition(ProgramState.DECISION, 4, "program-write-failure"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(screeningRepository).saveAllAndFlush(List.of(screening));
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    void auditFailureAbortsTheTransactionalCommand() {
        ProgramEntity program = program(ProgramState.SUBMISSION, 1);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(auditLoggingService.recordUserAction(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit unavailable"));

        assertThatThrownBy(() -> transition(ProgramState.ASSIGNMENT, 1, "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(programRepository).saveAndFlush(program);
    }

    @Test
    void automaticRejectionAuditFailureAbortsBeforeProgramMutationAndUserAudit() {
        ScreeningEntity screening = screening(ScreeningState.APPROVED, null, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.FINAL_PUBLICATION, 0)));
        when(screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(PROGRAM_ID))
                .thenReturn(List.of(screening));
        doThrow(new IllegalStateException("screening audit unavailable"))
                .when(auditLoggingService).recordSystemAction(any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> transition(ProgramState.DECISION, 0, "audit-failure-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("screening audit unavailable");
        verify(screeningRepository).saveAllAndFlush(List.of(screening));
        verify(programRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void returnsStoredReplayWithoutLockingOrApplyingEffects() throws Exception {
        ProgramTransitionResponse stored = new ProgramTransitionResponse(
                PROGRAM_ID, ProgramState.CREATED, ProgramState.SUBMISSION, 1, NOW, 0);
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("PROGRAM.TRANSITION"), eq("replay-key"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));

        ProgramCommandResult<ProgramTransitionResponse> result =
                transition(ProgramState.SUBMISSION, 0, "replay-key");

        assertThat(result.replayed()).isTrue();
        assertThat(result.body()).isEqualTo(stored);
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(screeningRepository, never()).findApprovedWithoutFinalSubmissionForUpdate(any());
    }

    @Test
    @SuppressWarnings("rawtypes")
    void hashesProgramRouteExpectedVersionAndTargetStateForIdempotency() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.SUBMISSION, 9)));

        transition(ProgramState.ASSIGNMENT, 9, "canonical-key");

        org.mockito.ArgumentCaptor<Object> canonicalRequest =
                org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(idempotencyManager).execute(
                eq("PROGRAM.TRANSITION"), eq("canonical-key"), canonicalRequest.capture(), any());
        assertThat((Map) canonicalRequest.getValue())
                .containsEntry("programId", PROGRAM_ID)
                .containsEntry("expectedVersion", 9L)
                .containsEntry("targetState", ProgramState.ASSIGNMENT);
    }

    @Test
    void idempotencyPayloadMismatchDoesNotLockOrMutate() {
        when(idempotencyManager.execute(eq("PROGRAM.TRANSITION"), eq("same-key"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));

        assertThatThrownBy(() -> transition(ProgramState.ASSIGNMENT, 1, "same-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void rejectsStaleVersionAndPropagatesOptimisticWriteConflict() {
        ProgramEntity stale = program(ProgramState.CREATED, 3);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> transition(ProgramState.SUBMISSION, 2, "stale"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);

        doThrow(new ObjectOptimisticLockingFailureException(ProgramEntity.class, PROGRAM_ID))
                .when(programRepository).saveAndFlush(stale);
        assertThatThrownBy(() -> transition(ProgramState.SUBMISSION, 3, "race"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void concurrentAttemptCannotSkipFromTheStateObservedUnderLock() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.SUBMISSION, 1)));

        assertThatThrownBy(() -> transition(ProgramState.REVIEW, 1, "concurrent"))
                .isInstanceOf(InvalidStateException.class);
        verify(programRepository, never()).saveAndFlush(any());
    }

    @Test
    void declaresOneTransactionBoundaryAroundLockEffectsAuditsAndIdempotency() throws Exception {
        assertThat(ProgramLifecycleService.class.getMethod(
                "transition", UUID.class, long.class, ProgramTransitionRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private void assertPrerequisiteFailure(ProgramState targetState) {
        assertThatThrownBy(() -> transition(targetState, 0, "key"))
                .isInstanceOf(ProgramTransitionPrerequisiteException.class)
                .extracting(error -> ((ProgramTransitionPrerequisiteException) error).errorCode())
                .isEqualTo("PROGRAM_TRANSITION_PREREQUISITE_FAILED");
        verify(programRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    private ProgramCommandResult<ProgramTransitionResponse> transition(
            ProgramState targetState,
            long version,
            String key) {
        return service.transition(PROGRAM_ID, version, new ProgramTransitionRequest(targetState), key);
    }

    private ProgramEntity program(ProgramState state, long version) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID, actor, "Festival", "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-01"), NOW);
        set(program, "state", state);
        set(program, "version", version);
        return program;
    }

    private ScreeningEntity screening(ScreeningState state, Instant finalSubmittedAt, long version) {
        return screening(SCREENING_ID, state, finalSubmittedAt, version);
    }

    private ScreeningEntity screening(
            UUID screeningId,
            ScreeningState state,
            Instant finalSubmittedAt,
            long version) {
        ScreeningEntity screening = new ScreeningEntity(
                screeningId, program(ProgramState.FINAL_PUBLICATION, 0), actor,
                "Film", "Cast", "Drama", 90, "A", null, null, NOW);
        set(screening, "state", state);
        set(screening, "finalSubmittedAt", finalSubmittedAt);
        set(screening, "version", version);
        return screening;
    }

    private static Stream<Arguments> legalTransitions() {
        return Stream.of(
                Arguments.of(ProgramState.CREATED, ProgramState.SUBMISSION),
                Arguments.of(ProgramState.SUBMISSION, ProgramState.ASSIGNMENT),
                Arguments.of(ProgramState.ASSIGNMENT, ProgramState.REVIEW),
                Arguments.of(ProgramState.REVIEW, ProgramState.SCHEDULING),
                Arguments.of(ProgramState.SCHEDULING, ProgramState.FINAL_PUBLICATION),
                Arguments.of(ProgramState.FINAL_PUBLICATION, ProgramState.DECISION),
                Arguments.of(ProgramState.DECISION, ProgramState.ANNOUNCED));
    }

    private static Stream<Arguments> illegalTransitions() {
        return Stream.of(ProgramState.values()).flatMap(oldState ->
                Stream.of(ProgramState.values())
                        .filter(targetState -> !oldState.canTransitionTo(targetState))
                        .map(targetState -> Arguments.of(oldState, targetState)));
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
