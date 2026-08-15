package com.example.cinema.screening.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.screening.api.ScreeningDetailResponse;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;

import tools.jackson.databind.ObjectMapper;

class ScreeningSubmissionServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROGRAM_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SCREENING_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant CREATED_AT = Instant.parse("2026-08-15T09:00:00Z");
    private static final Instant START = Instant.parse("2027-02-01T10:00:00Z");
    private static final Instant END = Instant.parse("2027-02-01T12:00:00Z");

    private final ScreeningRepository screeningRepository = mock(ScreeningRepository.class);
    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ContextAwareAuthorizationService authorization = mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity user = new UserEntity(USER_ID, "alice", "hash-never-exposed", "Alice Submitter");
    private final ScreeningSubmissionService service = new ScreeningSubmissionService(
            screeningRepository,
            programRepository,
            authorization,
            idempotencyManager,
            auditLoggingService,
            objectMapper);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(authorization.currentUser()).thenReturn(
                new AuthenticatedUserIdentity(USER_ID, "alice", "Alice Submitter"));
        when(screeningRepository.saveAndFlush(any(ScreeningEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            encodedResponse.set(invocation.getArgument(0));
            return "{\"stored\":true}";
        });
        when(objectMapper.readValue(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<Object> responseType = invocation.getArgument(1);
            return responseType.cast(encodedResponse.get());
        });
        when(idempotencyManager.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResponse> command = invocation.getArgument(3);
            StoredCommandResponse response = command.get();
            return new IdempotencyResult(response.status(), response.body(), false);
        });
    }

    @Test
    void submitsCompleteOwnedDraftUnderLockAndAuditsTheFrozenContent() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 2);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        ScreeningCommandResult<ScreeningDetailResponse> result =
                service.submit(SCREENING_ID, 2, "submit-key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body().screeningId()).isEqualTo(SCREENING_ID);
        assertThat(result.body().programId()).isEqualTo(PROGRAM_ID);
        assertThat(result.body().state()).isEqualTo(ScreeningState.SUBMITTED);
        assertThat(result.body().filmTitle()).isEqualTo("Film");
        assertThat(result.body().candidateAuditoriumName()).isEqualTo("Candidate Hall");
        assertThat(result.body().finalAuditoriumName()).isNull();
        assertThat(result.body().finalSubmittedAt()).isNull();
        assertThat(result.body().submitter().userId()).isEqualTo(USER_ID);
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> oldValues = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(USER_ID), eq("SCREENING_SUBMITTED"), eq("SCREENING"), eq(SCREENING_ID),
                oldValues.capture(), newValues.capture(), eq(null));
        assertThat(oldValues.getValue()).containsEntry("state", ScreeningState.CREATED);
        assertThat(newValues.getValue())
                .containsEntry("state", ScreeningState.SUBMITTED)
                .containsEntry("filmTitle", "Film")
                .containsEntry("candidateAuditoriumName", "Candidate Hall")
                .containsEntry("finalSubmittedAt", null);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> canonical = ArgumentCaptor.forClass(Map.class);
        verify(idempotencyManager).execute(
                eq("SCREENING.SUBMIT"), eq("submit-key"), canonical.capture(), any());
        assertThat(canonical.getValue())
                .containsEntry("screeningId", SCREENING_ID)
                .containsEntry("expectedVersion", 2L);

        InOrder flow = inOrder(screeningRepository, programRepository);
        flow.verify(screeningRepository).findActiveById(SCREENING_ID);
        flow.verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        flow.verify(programRepository).findById(PROGRAM_ID);
        flow.verify(screeningRepository).saveAndFlush(screening);
    }

    @ParameterizedTest
    @MethodSource("missingMandatoryFields")
    void reportsEveryMissingMandatoryField(String entityField, String apiField) {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        set(screening, entityField, null);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "missing-" + apiField))
                .isInstanceOfSatisfying(FieldValidationException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("SCREENING_SUBMISSION_INVALID");
                    assertThat(exception.fieldErrors())
                            .extracting(error -> error.field())
                            .contains(apiField);
                });
        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("blankMandatoryTextFields")
    void reportsEveryBlankMandatoryTextField(String entityField, String apiField) {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        set(screening, entityField, "   ");
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertSubmissionFieldError(screening, apiField);
    }

    @ParameterizedTest
    @MethodSource("invalidDurationAndIntervals")
    void reportsInvalidDurationAndTimeCombinations(
            Integer duration,
            Instant start,
            Instant end,
            String field) {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        set(screening, "durationMinutes", duration);
        set(screening, "startTime", start);
        set(screening, "endTime", end);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertSubmissionFieldError(screening, field);
    }

    @Test
    void returnsAllIncompleteFieldsInOneControlledValidationError() {
        ScreeningEntity screening = new ScreeningEntity(
                SCREENING_ID, program(ProgramState.SUBMISSION), user,
                null, null, null, null, null, null, null, CREATED_AT);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "all-missing"))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors())
                                .extracting(error -> error.field())
                                .containsExactly(
                                        "filmTitle", "cast", "genre", "durationMinutes",
                                        "candidateAuditoriumName", "startTime", "endTime"));
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "SUBMISSION", mode = EnumSource.Mode.EXCLUDE)
    void rejectsEveryWrongProgramState(ProgramState state) {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        allowSubmission(screening, program(state));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "program-" + state))
                .isInstanceOf(InvalidStateException.class);
        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = ScreeningState.class, names = "CREATED", mode = EnumSource.Mode.EXCLUDE)
    void rejectsEveryWrongScreeningState(ScreeningState state) {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        set(screening, "state", state);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "screening-" + state))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsNonOwnerBeforeIdempotencyAndMutation() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        doThrow(new ForbiddenException()).when(authorization).requireOwner(screening);

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "not-owner"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void rejectsProgrammerOrStaffRoleConflictBeforeIdempotency() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        doThrow(new ForbiddenException()).when(authorization).requireSubmitter(PROGRAM_ID);

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "conflicting-role"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void concealsMissingOrDeletedScreeningBeforeAuthorizationAndIdempotency() {
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "deleted"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(SCREENING_ID.toString());
        verify(authorization, never()).requireOwner(any());
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @Test
    void revalidatesOwnerAndSubmitterAfterAcquiringTheScreeningLock() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        allowSubmission(screening, program(ProgramState.SUBMISSION));
        doNothing().doThrow(new ForbiddenException()).when(authorization).requireOwner(screening);

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "recheck"))
                .isInstanceOf(ForbiddenException.class);
        verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsExplicitAndJpaOptimisticConflicts() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 4);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 3, "stale"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);

        when(screeningRepository.saveAndFlush(screening)).thenThrow(
                new ObjectOptimisticLockingFailureException(ScreeningEntity.class, SCREENING_ID));
        assertThatThrownBy(() -> service.submit(SCREENING_ID, 4, "jpa-race"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void candidateOverbookingIsAllowedWithoutAnyFinalScheduleConflictQuery() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        set(screening, "candidateAuditoriumName", "Already Requested Hall");
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        service.submit(SCREENING_ID, 0, "overbooking");

        verify(screeningRepository, never()).findSchedulingConflictsForUpdate(any(), any(), any(), any());
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
    }

    @Test
    void exactReplayReturnsStoredSuccessWithoutLockingOrSubmittingAgain() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 2);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        ScreeningDetailResponse stored = responseFor(screening, ScreeningState.SUBMITTED);
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("SCREENING.SUBMIT"), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));

        ScreeningCommandResult<ScreeningDetailResponse> replay =
                service.submit(SCREENING_ID, 2, "replay");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(stored);
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void payloadMismatchAndInProgressDuplicateDoNotAcquireTheScreeningLock() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 2);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(idempotencyManager.execute(eq("SCREENING.SUBMIT"), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        when(idempotencyManager.execute(eq("SCREENING.SUBMIT"), eq("in-progress"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_REQUEST_IN_PROGRESS", "The request is in progress.", true));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 3, "mismatch"))
                .isInstanceOfSatisfying(IdempotencyConflictException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThatThrownBy(() -> service.submit(SCREENING_ID, 2, "in-progress"))
                .isInstanceOfSatisfying(IdempotencyConflictException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("IDEMPOTENCY_REQUEST_IN_PROGRESS"));
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void secondConcurrentCommandCannotExecuteTheTransitionAgain() {
        ScreeningEntity screening = completeScreening(ProgramState.SUBMISSION, 0);
        allowSubmission(screening, program(ProgramState.SUBMISSION));

        service.submit(SCREENING_ID, 0, "first");
        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "second"))
                .isInstanceOf(InvalidStateException.class);

        verify(screeningRepository, times(1)).saveAndFlush(screening);
        verify(auditLoggingService, times(1)).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void persistenceAndAuditFailuresPropagateInsideTheTransaction() {
        ScreeningEntity persistenceFailure = completeScreening(ProgramState.SUBMISSION, 0);
        allowSubmission(persistenceFailure, program(ProgramState.SUBMISSION));
        when(screeningRepository.saveAndFlush(persistenceFailure))
                .thenThrow(new DataIntegrityViolationException("simulated database failure"));

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "db-failure"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());

        ScreeningEntity auditFailure = completeScreening(ProgramState.SUBMISSION, 0);
        allowSubmission(auditFailure, program(ProgramState.SUBMISSION));
        when(screeningRepository.saveAndFlush(auditFailure)).thenReturn(auditFailure);
        doThrow(new IllegalStateException("audit unavailable")).when(auditLoggingService)
                .recordUserAction(any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "audit-failure"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void submissionDeclaresTheRequiredTransactionBoundary() throws Exception {
        assertThat(ScreeningSubmissionService.class
                .getMethod("submit", UUID.class, long.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertSubmissionFieldError(ScreeningEntity screening, String expectedField) {
        assertThatThrownBy(() -> service.submit(SCREENING_ID, 0, "invalid-" + expectedField))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors())
                                .extracting(error -> error.field())
                                .contains(expectedField));
        assertThat(screening.getState()).isEqualTo(ScreeningState.CREATED);
    }

    private void allowSubmission(ScreeningEntity screening, ProgramEntity currentProgram) {
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(currentProgram));
    }

    private ScreeningEntity completeScreening(ProgramState programState, long version) {
        ScreeningEntity screening = new ScreeningEntity(
                SCREENING_ID,
                program(programState),
                user,
                "Film",
                "Cast",
                "Drama",
                120,
                "Candidate Hall",
                START,
                END,
                CREATED_AT);
        set(screening, "version", version);
        return screening;
    }

    private ProgramEntity program(ProgramState targetState) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID,
                user,
                "Program",
                "Description",
                LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-12-31"),
                CREATED_AT);
        while (program.getState() != targetState) {
            program.transitionTo(program.getState().next().orElseThrow());
        }
        return program;
    }

    private static ScreeningDetailResponse responseFor(
            ScreeningEntity screening,
            ScreeningState state) {
        return new ScreeningDetailResponse(
                screening.getId(), screening.getProgram().getId(), screening.getFilmTitle(),
                screening.getCastText(), screening.getGenre(), screening.getDurationMinutes(),
                screening.getCandidateAuditoriumName(), null, screening.getStartTime(), screening.getEndTime(),
                state, null, null, null,
                new com.example.cinema.program.api.UserSummaryResponse(
                        USER_ID, "alice", "Alice Submitter"),
                null, screening.getCreatedAt(), screening.getVersion());
    }

    private static Stream<Arguments> missingMandatoryFields() {
        return Stream.of(
                Arguments.of("filmTitle", "filmTitle"),
                Arguments.of("castText", "cast"),
                Arguments.of("genre", "genre"),
                Arguments.of("durationMinutes", "durationMinutes"),
                Arguments.of("candidateAuditoriumName", "candidateAuditoriumName"),
                Arguments.of("startTime", "startTime"),
                Arguments.of("endTime", "endTime"));
    }

    private static Stream<Arguments> blankMandatoryTextFields() {
        return Stream.of(
                Arguments.of("filmTitle", "filmTitle"),
                Arguments.of("castText", "cast"),
                Arguments.of("genre", "genre"),
                Arguments.of("candidateAuditoriumName", "candidateAuditoriumName"));
    }

    private static Stream<Arguments> invalidDurationAndIntervals() {
        return Stream.of(
                Arguments.of(0, START, END, "durationMinutes"),
                Arguments.of(-1, START, END, "durationMinutes"),
                Arguments.of(120, START, START, "endTime"),
                Arguments.of(120, END, START, "endTime"),
                Arguments.of(121, START, END, "endTime"));
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
