package com.example.cinema.screening.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.screening.api.ScreeningCreateRequest;
import com.example.cinema.screening.api.ScreeningDetailResponse;
import com.example.cinema.screening.api.ScreeningUpdateRequest;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;
import com.example.cinema.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

class ScreeningPreparationServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PROGRAM_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID SCREENING_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final Instant START = Instant.parse("2027-02-01T10:00:00Z");
    private static final Instant END = Instant.parse("2027-02-01T12:00:00Z");

    private final ScreeningRepository screeningRepository = mock(ScreeningRepository.class);
    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ProgramRoleRepository roleRepository = mock(ProgramRoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContextAwareAuthorizationService authorization = mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity user = new UserEntity(USER_ID, "alice", "hash-never-exposed", "Alice Submitter");
    private final ScreeningPreparationService service = new ScreeningPreparationService(
            screeningRepository,
            programRepository,
            roleRepository,
            userRepository,
            authorization,
            idempotencyManager,
            auditLoggingService,
            objectMapper,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(authorization.currentUser()).thenReturn(
                new AuthenticatedUserIdentity(USER_ID, "alice", "Alice Submitter"));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(screeningRepository.saveAndFlush(any(ScreeningEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.saveAndFlush(any(ProgramRoleEntity.class)))
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
    void ordinaryUserCreatesPartialDraftGetsSubmitterRoleAndAuditAtomically() {
        ProgramEntity program = program(ProgramState.CREATED);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());

        ScreeningCommandResult<ScreeningDetailResponse> result = service.create(
                PROGRAM_ID,
                new ScreeningCreateRequest("  Film  ", null, null, null, null, START, null),
                "create-key");

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.replayed()).isFalse();
        assertThat(result.body().programId()).isEqualTo(PROGRAM_ID);
        assertThat(result.body().filmTitle()).isEqualTo("Film");
        assertThat(result.body().cast()).isNull();
        assertThat(result.body().state()).isEqualTo(ScreeningState.CREATED);
        assertThat(result.body().createdAt()).isEqualTo(NOW);
        assertThat(result.body().version()).isZero();
        assertThat(result.body().submitter().userId()).isEqualTo(USER_ID);
        assertThat(result.body().handler()).isNull();
        assertThat(result.body().finalAuditoriumName()).isNull();
        assertThat(result.body().finalSubmittedAt()).isNull();

        ArgumentCaptor<ProgramRoleEntity> role = ArgumentCaptor.forClass(ProgramRoleEntity.class);
        verify(roleRepository).saveAndFlush(role.capture());
        assertThat(role.getValue().getRole()).isEqualTo(ProgramRoleType.SUBMITTER);
        assertThat(role.getValue().getAssignedAt()).isEqualTo(NOW);
        assertThat(role.getValue().getAssignedBy()).isSameAs(user);
        verify(auditLoggingService).recordUserAction(
                eq(USER_ID), eq("SCREENING_CREATED"), eq("SCREENING"), any(UUID.class),
                eq(Map.of()), any(), eq(null));
        InOrder lockThenRoleCheck = inOrder(programRepository, roleRepository);
        lockThenRoleCheck.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        lockThenRoleCheck.verify(roleRepository).findRole(PROGRAM_ID, USER_ID);
    }

    @Test
    void missingProgramReturnsSafeNotFoundWithoutRoleScreeningOrAuditWrites() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "missing-program"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(PROGRAM_ID.toString());
        verify(roleRepository, never()).saveAndFlush(any());
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void existingSubmitterMayCreateASecondDraftWithoutAnotherRoleAssignment() {
        ProgramEntity program = program(ProgramState.SUBMISSION);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(program, user, ProgramRoleType.SUBMITTER, NOW, user)));

        service.create(PROGRAM_ID, completeRequest("Same Candidate Hall"), "second-draft");

        verify(roleRepository, never()).saveAndFlush(any());
        verify(screeningRepository).saveAndFlush(any(ScreeningEntity.class));
    }

    @Test
    void concurrentConflictingRoleAssignmentIsTranslatedWithoutCreatingOrAuditingAScreening() {
        ProgramEntity program = program(ProgramState.CREATED);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());
        when(roleRepository.saveAndFlush(any(ProgramRoleEntity.class)))
                .thenThrow(new DataIntegrityViolationException("simulated role race"));

        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "role-race"))
                .isInstanceOf(RoleConflictException.class)
                .extracting(error -> ((RoleConflictException) error).errorCode())
                .isEqualTo("ROLE_CONFLICT");

        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = ProgramRoleType.class, names = {"PROGRAMMER", "STAFF"})
    void programmerAndStaffCannotCreateDraftsInTheirProgram(ProgramRoleType roleType) {
        ProgramEntity program = program(ProgramState.CREATED);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(program, user, roleType, NOW, user)));

        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "role-conflict"))
                .isInstanceOf(RoleConflictException.class)
                .extracting(error -> ((RoleConflictException) error).errorCode())
                .isEqualTo("ROLE_CONFLICT");
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"CREATED", "SUBMISSION"})
    void createsDraftsInEveryAllowedProgramPhase(ProgramState state) {
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program(state)));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());

        assertThat(service.create(PROGRAM_ID, completeRequest("Hall"), "allowed-" + state).body().state())
                .isEqualTo(ScreeningState.CREATED);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = {"CREATED", "SUBMISSION"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsDraftCreationAfterSubmissionsClose(ProgramState state) {
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program(state)));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "closed-" + state))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @MethodSource("invalidDrafts")
    void rejectsEveryInvalidSuppliedDraftCombination(ScreeningCreateRequest request, String errorCode) {
        assertThatThrownBy(() -> service.create(PROGRAM_ID, request, "invalid"))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @Test
    void acceptsCandidateOverbookingWithoutCallingFinalScheduleConflictQuery() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.SUBMISSION)));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());

        service.create(PROGRAM_ID, completeRequest("Already Requested Hall"), "overbooking-allowed");

        verify(screeningRepository, never()).findSchedulingConflictsForUpdate(any(), any(), any(), any());
        verify(screeningRepository).saveAndFlush(any(ScreeningEntity.class));
    }

    @Test
    void ownerUpdatesOnlyEditableDraftFieldsAndAuditsOldAndNewValues() {
        ScreeningEntity screening = screening(ProgramState.SUBMISSION, ScreeningState.CREATED, 2);
        stubActive(screening);
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setFilmTitle("  Revised Film  ");
        patch.setDurationMinutes(100);

        ScreeningCommandResult<ScreeningDetailResponse> result =
                service.update(SCREENING_ID, 2, patch, "update-key");

        verify(authorization).requireOwner(screening);
        verify(authorization).requireSubmitter(PROGRAM_ID);
        assertThat(result.body().filmTitle()).isEqualTo("Revised Film");
        assertThat(result.body().durationMinutes()).isEqualTo(100);
        assertThat(result.body().candidateAuditoriumName()).isEqualTo("Candidate Hall");
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> oldValues = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newValues = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(USER_ID), eq("SCREENING_DRAFT_UPDATED"), eq("SCREENING"), eq(SCREENING_ID),
                oldValues.capture(), newValues.capture(), eq(null));
        assertThat(oldValues.getValue()).containsEntry("filmTitle", "Film");
        assertThat(newValues.getValue()).containsEntry("filmTitle", "Revised Film");
    }

    @Test
    void rejectsEmptyNullAndResultingInvalidDraftUpdates() {
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        stubActive(screening);

        assertUpdateCode(new ScreeningUpdateRequest(), "EMPTY_SCREENING_UPDATE");
        ScreeningUpdateRequest nullTitle = new ScreeningUpdateRequest();
        nullTitle.setFilmTitle(null);
        assertUpdateCode(nullTitle, "VALIDATION_FAILED");
        ScreeningUpdateRequest tooLong = new ScreeningUpdateRequest();
        tooLong.setDurationMinutes(121);
        assertUpdateCode(tooLong, "INVALID_SCREENING_INTERVAL");
        ScreeningUpdateRequest reversed = new ScreeningUpdateRequest();
        reversed.setStartTime(END.plusSeconds(1));
        assertUpdateCode(reversed, "INVALID_SCREENING_INTERVAL");
        ScreeningUpdateRequest equal = new ScreeningUpdateRequest();
        equal.setEndTime(START);
        assertUpdateCode(equal, "INVALID_SCREENING_INTERVAL");
    }

    @Test
    void updateRequiresOwnerAndSubmitterRelationship() {
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        stubActive(screening);
        doThrow(new ForbiddenException()).when(authorization).requireOwner(screening);
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");

        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "forbidden"))
                .isInstanceOf(ForbiddenException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void entityOwnerWithoutSubmitterProgramRoleCannotUpdateOrWithdraw() {
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        stubActive(screening);
        doThrow(new ForbiddenException()).when(authorization).requireSubmitter(PROGRAM_ID);
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");

        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "missing-role"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.withdraw(SCREENING_ID, 0))
                .isInstanceOf(ForbiddenException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateRequiresActiveCreatedScreeningBeforeProgramPassesSubmission() {
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");

        ScreeningEntity submitted = screening(ProgramState.SUBMISSION, ScreeningState.SUBMITTED, 0);
        stubActive(submitted);
        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "submitted"))
                .isInstanceOf(InvalidStateException.class);

        ScreeningEntity late = screening(ProgramState.ASSIGNMENT, ScreeningState.CREATED, 0);
        stubActive(late);
        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "late"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void updateTranslatesExplicitAndJpaOptimisticConflictsSafely() {
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 4);
        stubActive(screening);

        assertThatThrownBy(() -> service.update(SCREENING_ID, 3, patch, "stale"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);

        when(screeningRepository.saveAndFlush(screening)).thenThrow(
                new ObjectOptimisticLockingFailureException(ScreeningEntity.class, SCREENING_ID));
        assertThatThrownBy(() -> service.update(SCREENING_ID, 4, patch, "race"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void exactUpdateReplayReturnsStoredResponseAndPayloadMismatchDoesNotMutate() {
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 2);
        stubActive(screening);
        ScreeningDetailResponse stored = responseFor(screening);
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("SCREENING.UPDATE"), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");

        ScreeningCommandResult<ScreeningDetailResponse> replay =
                service.update(SCREENING_ID, 2, patch, "replay");
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(stored);
        verify(screeningRepository, never()).findActiveProgramIdById(any());
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(screeningRepository, never()).saveAndFlush(any());

        when(idempotencyManager.execute(eq("SCREENING.UPDATE"), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertThatThrownBy(() -> service.update(SCREENING_ID, 2, patch, "mismatch"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void exactCreationReplayDoesNotCreateAnotherScreeningOrRole() {
        ScreeningEntity screening = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        ScreeningDetailResponse stored = responseFor(screening);
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("SCREENING.CREATE"), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(201, "{\"stored\":true}", true));

        ScreeningCommandResult<ScreeningDetailResponse> replay =
                service.create(PROGRAM_ID, completeRequest("Hall"), "replay");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(stored);
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(roleRepository, never()).saveAndFlush(any());
        verify(screeningRepository, never()).saveAndFlush(any());

        when(idempotencyManager.execute(eq("SCREENING.CREATE"), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertThatThrownBy(() -> service.create(
                PROGRAM_ID, completeRequest("Different Hall"), "mismatch"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void createdAndSubmittedScreeningsWithdrawOnlyInsideTheirAllowedWindows() {
        ScreeningEntity created = screening(ProgramState.CREATED, ScreeningState.CREATED, 1);
        stubActive(created);
        service.withdraw(SCREENING_ID, 1);
        assertThat(created.getDeletedAt()).isEqualTo(NOW);
        verify(authorization).requireOwner(created);
        verify(authorization).requireSubmitter(PROGRAM_ID);
        verify(auditLoggingService).recordUserAction(
                eq(USER_ID), eq("SCREENING_WITHDRAWN"), eq("SCREENING"), eq(SCREENING_ID),
                any(), any(), eq(null));

        ScreeningEntity submitted = screening(ProgramState.SUBMISSION, ScreeningState.SUBMITTED, 3);
        stubActive(submitted);
        service.withdraw(SCREENING_ID, 3);
        assertThat(submitted.getDeletedAt()).isEqualTo(NOW);
    }

    @ParameterizedTest
    @MethodSource("forbiddenWithdrawals")
    void rejectsEveryForbiddenWithdrawalState(ProgramState programState, ScreeningState screeningState) {
        ScreeningEntity screening = screening(programState, screeningState, 0);
        stubActive(screening);

        assertThatThrownBy(() -> service.withdraw(SCREENING_ID, 0))
                .isInstanceOf(InvalidStateException.class);
        assertThat(screening.getDeletedAt()).isNull();
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatedWithdrawalIsConcealedAndDoesNotCreateAnotherAudit() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.withdraw(SCREENING_ID, 0))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(SCREENING_ID.toString());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateLocksAndRechecksTheCurrentProgramPhaseBeforeLoadingTheDraft() {
        ScreeningEntity screening = screening(ProgramState.SUBMISSION, ScreeningState.CREATED, 0);
        ProgramEntity lockedProgram = program(ProgramState.ASSIGNMENT);
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(lockedProgram));
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("Drama");

        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "phase-race"))
                .isInstanceOf(InvalidStateException.class);

        InOrder locks = inOrder(screeningRepository, programRepository);
        locks.verify(screeningRepository).findActiveProgramIdById(SCREENING_ID);
        locks.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        locks.verify(screeningRepository).findActiveById(SCREENING_ID);
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void withdrawalLocksProgramBeforeScreeningAndRechecksTheCurrentPhase() {
        ScreeningEntity screening = screening(ProgramState.SUBMISSION, ScreeningState.CREATED, 0);
        ProgramEntity lockedProgram = program(ProgramState.ASSIGNMENT);
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(lockedProgram));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));

        assertThatThrownBy(() -> service.withdraw(SCREENING_ID, 0))
                .isInstanceOf(InvalidStateException.class);

        InOrder locks = inOrder(screeningRepository, programRepository);
        locks.verify(screeningRepository).findActiveProgramIdById(SCREENING_ID);
        locks.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        locks.verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        assertThat(screening.getDeletedAt()).isNull();
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void screeningWriteFailureStopsCreationBeforeAuditAndReliesOnTheTransactionForRoleRollback() {
        ProgramEntity program = program(ProgramState.CREATED);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());
        when(screeningRepository.saveAndFlush(any(ScreeningEntity.class)))
                .thenThrow(new DataIntegrityViolationException("simulated write failure"));

        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "write-failure"))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(roleRepository).saveAndFlush(any(ProgramRoleEntity.class));
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void auditFailurePropagatesSoCreateUpdateAndWithdrawalTransactionsCannotComplete() {
        doThrow(new IllegalStateException("audit unavailable")).when(auditLoggingService)
                .recordUserAction(any(), any(), any(), any(), any(), any(), any());

        ProgramEntity program = program(ProgramState.CREATED);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(PROGRAM_ID, completeRequest("Hall"), "audit-create"))
                .isInstanceOf(IllegalStateException.class);

        ScreeningEntity update = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        stubActive(update);
        ScreeningUpdateRequest patch = new ScreeningUpdateRequest();
        patch.setGenre("New Genre");
        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "audit-update"))
                .isInstanceOf(IllegalStateException.class);

        ScreeningEntity withdrawal = screening(ProgramState.CREATED, ScreeningState.CREATED, 0);
        stubActive(withdrawal);
        assertThatThrownBy(() -> service.withdraw(SCREENING_ID, 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyPreparationMutationDeclaresATransactionBoundary() throws Exception {
        assertThat(ScreeningPreparationService.class
                .getMethod("create", UUID.class, ScreeningCreateRequest.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(ScreeningPreparationService.class
                .getMethod("update", UUID.class, long.class, ScreeningUpdateRequest.class, String.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(ScreeningPreparationService.class
                .getMethod("withdraw", UUID.class, long.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    private void assertUpdateCode(ScreeningUpdateRequest patch, String expectedCode) {
        assertThatThrownBy(() -> service.update(SCREENING_ID, 0, patch, "invalid-update"))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(expectedCode));
    }

    private ProgramEntity program(ProgramState targetState) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID, user, "Program", "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-12-31"), NOW);
        while (program.getState() != targetState) {
            program.transitionTo(program.getState().next().orElseThrow());
        }
        return program;
    }

    private ScreeningEntity screening(ProgramState programState, ScreeningState state, long version) {
        ScreeningEntity screening = new ScreeningEntity(
                SCREENING_ID, program(programState), user,
                "Film", "Cast", "Genre", 120, "Candidate Hall", START, END, NOW);
        set(screening, "state", state);
        set(screening, "version", version);
        return screening;
    }

    private void stubActive(ScreeningEntity screening) {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(screening.getProgram()));
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
    }

    private static ScreeningCreateRequest completeRequest(String hall) {
        return new ScreeningCreateRequest("Film", "Cast", "Genre", 120, hall, START, END);
    }

    private static ScreeningDetailResponse responseFor(ScreeningEntity screening) {
        return new ScreeningDetailResponse(
                screening.getId(), screening.getProgram().getId(), screening.getFilmTitle(),
                screening.getCastText(), screening.getGenre(), screening.getDurationMinutes(),
                screening.getCandidateAuditoriumName(), null, screening.getStartTime(), screening.getEndTime(),
                screening.getState(), null, null, null,
                new com.example.cinema.program.api.UserSummaryResponse(
                        USER_ID, "alice", "Alice Submitter"),
                null, screening.getCreatedAt(), screening.getVersion());
    }

    private static Stream<Arguments> invalidDrafts() {
        return Stream.of(
                Arguments.of(new ScreeningCreateRequest(" ", null, null, null, null, null, null),
                        "VALIDATION_FAILED"),
                Arguments.of(new ScreeningCreateRequest(null, "\t", null, null, null, null, null),
                        "VALIDATION_FAILED"),
                Arguments.of(new ScreeningCreateRequest(null, null, "\n", null, null, null, null),
                        "VALIDATION_FAILED"),
                Arguments.of(new ScreeningCreateRequest(null, null, null, null, "  ", null, null),
                        "VALIDATION_FAILED"),
                Arguments.of(new ScreeningCreateRequest(null, null, null, 0, null, null, null),
                        "INVALID_SCREENING_DURATION"),
                Arguments.of(new ScreeningCreateRequest(null, null, null, null, null, START, START),
                        "INVALID_SCREENING_INTERVAL"),
                Arguments.of(new ScreeningCreateRequest(null, null, null, null, null, END, START),
                        "INVALID_SCREENING_INTERVAL"),
                Arguments.of(new ScreeningCreateRequest(null, null, null, 121, null, START, END),
                        "INVALID_SCREENING_INTERVAL"));
    }

    private static Stream<Arguments> forbiddenWithdrawals() {
        Stream<Arguments> submittedBeforeSubmission = Stream.of(
                Arguments.of(ProgramState.CREATED, ScreeningState.SUBMITTED));
        Stream<Arguments> afterAssignment = Stream.of(
                ProgramState.ASSIGNMENT,
                ProgramState.REVIEW,
                ProgramState.SCHEDULING,
                ProgramState.FINAL_PUBLICATION,
                ProgramState.DECISION,
                ProgramState.ANNOUNCED)
                .flatMap(programState -> Stream.of(ScreeningState.CREATED, ScreeningState.SUBMITTED)
                        .map(screeningState -> Arguments.of(programState, screeningState)));
        Stream<Arguments> nonWithdrawableScreeningStates = Stream.of(
                ScreeningState.REVIEWED,
                ScreeningState.APPROVED,
                ScreeningState.SCHEDULED,
                ScreeningState.REJECTED)
                .map(screeningState -> Arguments.of(ProgramState.SUBMISSION, screeningState));
        return Stream.concat(submittedBeforeSubmission, Stream.concat(afterAssignment, nonWithdrawableScreeningStates));
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
