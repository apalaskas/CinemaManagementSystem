package com.example.cinema.program.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.CreatorRoleRequiredException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ProgramNameExistsException;
import com.example.cinema.common.error.ProgramRoleExistsException;
import com.example.cinema.common.error.ProgramRoleNotFoundException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.api.ProgramCreateRequest;
import com.example.cinema.program.api.ProgramDetailResponse;
import com.example.cinema.program.api.ProgramRoleRequest;
import com.example.cinema.program.api.ProgramRoleResponse;
import com.example.cinema.program.api.ProgramUpdateRequest;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;
import com.example.cinema.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

class ProgramManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");
    private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TARGET_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ProgramRoleRepository roleRepository = mock(ProgramRoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContextAwareAuthorizationService authorization = mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity actor = user(ACTOR_ID, "alice", "Alice Programmer");
    private final UserEntity target = user(TARGET_ID, "bob", "Bob Target");
    private final ProgramManagementService service = new ProgramManagementService(
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
                new AuthenticatedUserIdentity(ACTOR_ID, "alice", "Alice Programmer"));
        when(userRepository.findById(ACTOR_ID)).thenReturn(Optional.of(actor));
        when(programRepository.saveAndFlush(any(ProgramEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(roleRepository.save(any(ProgramRoleEntity.class)))
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
    void createsProgramAssignsCreatorRoleAuditsAndReturnsCreatedDetails() {
        when(programRepository.existsByNameIgnoreCase("Festival 2027")).thenReturn(false);

        ProgramCommandResult<ProgramDetailResponse> result = service.create(
                createRequest("  Festival 2027  "), "create-key");

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().name()).isEqualTo("Festival 2027");
        assertThat(result.body().state()).isEqualTo(ProgramState.CREATED);
        assertThat(result.body().createdAt()).isEqualTo(NOW);
        assertThat(result.body().version()).isZero();
        assertThat(result.body().creator()).isEqualTo(
                new UserSummaryResponse(ACTOR_ID, "alice", "Alice Programmer"));

        ArgumentCaptor<ProgramRoleEntity> role = ArgumentCaptor.forClass(ProgramRoleEntity.class);
        verify(roleRepository).save(role.capture());
        assertThat(role.getValue().getRole()).isEqualTo(ProgramRoleType.PROGRAMMER);
        assertThat(role.getValue().getUser()).isSameAs(actor);
        assertThat(role.getValue().getAssignedBy()).isSameAs(actor);
        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_CREATED"), eq("PROGRAM"), any(UUID.class),
                any(), any(), eq(null));
    }

    @Test
    void rejectsInvalidCreationDateRangeBeforePersistence() {
        ProgramCreateRequest request = new ProgramCreateRequest(
                "Festival", "Description", LocalDate.parse("2027-06-02"), LocalDate.parse("2027-06-01"));

        assertThatThrownBy(() -> service.create(request, "key"))
                .isInstanceOf(InvalidInputException.class)
                .extracting(error -> ((InvalidInputException) error).errorCode())
                .isEqualTo("INVALID_DATE_RANGE");
        verify(programRepository, never()).save(any());
    }

    @Test
    void rejectsCaseInsensitiveDuplicateProgramName() {
        when(programRepository.existsByNameIgnoreCase("Festival 2027")).thenReturn(true);

        assertThatThrownBy(() -> service.create(createRequest("Festival 2027"), "key"))
                .isInstanceOf(ProgramNameExistsException.class)
                .extracting(error -> ((ProgramNameExistsException) error).errorCode())
                .isEqualTo("PROGRAM_NAME_EXISTS");
        verify(roleRepository, never()).save(any());
    }

    @Test
    void translatesDatabaseNameRaceToStableConflict() {
        when(programRepository.existsByNameIgnoreCase("Festival 2027")).thenReturn(false);
        when(programRepository.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("SQL uk_program_name"));

        assertThatThrownBy(() -> service.create(createRequest("Festival 2027"), "key"))
                .isInstanceOf(ProgramNameExistsException.class)
                .hasMessageNotContaining("SQL");
    }

    @Test
    void replaysStoredCreationWithoutCreatingAnotherProgram() throws Exception {
        ProgramDetailResponse stored = new ProgramDetailResponse(
                PROGRAM_ID, "Original", "Description", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-01-02"), ProgramState.CREATED, NOW, 0,
                new UserSummaryResponse(ACTOR_ID, "alice", "Alice Programmer"));
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("PROGRAM.CREATE"), eq("same-key"), any(), any()))
                .thenReturn(new IdempotencyResult(201, "{\"stored\":true}", true));

        ProgramCommandResult<ProgramDetailResponse> result = service.create(
                createRequest("Original"), "same-key");

        assertThat(result.replayed()).isTrue();
        assertThat(result.body()).isEqualTo(stored);
        verify(programRepository, never()).saveAndFlush(any());
        verify(roleRepository, never()).save(any());
    }

    @Test
    void propagatesIdempotencyPayloadMismatchWithoutMutation() {
        when(idempotencyManager.execute(eq("PROGRAM.CREATE"), eq("same-key"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));

        assertThatThrownBy(() -> service.create(createRequest("Different"), "same-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).saveAndFlush(any());
    }

    @Test
    void updatesCompleteAggregateBeforeAnnouncedAndAuditsOldAndNewValues() {
        ProgramEntity program = program(ProgramState.SCHEDULING, 4);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setName("  Revised Festival  ");
        patch.setEndDate(LocalDate.parse("2027-02-03"));

        ProgramCommandResult<ProgramDetailResponse> result =
                service.update(PROGRAM_ID, 4, patch, "update-key");

        verify(authorization, org.mockito.Mockito.times(2)).requireProgrammer(PROGRAM_ID);
        assertThat(result.body().name()).isEqualTo("Revised Festival");
        assertThat(result.body().endDate()).isEqualTo(LocalDate.parse("2027-02-03"));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> oldSnapshot = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newSnapshot = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_DETAILS_UPDATED"), eq("PROGRAM"), eq(PROGRAM_ID),
                oldSnapshot.capture(), newSnapshot.capture(), eq(null));
        assertThat(oldSnapshot.getValue())
                .containsEntry("name", "Festival")
                .containsEntry("endDate", LocalDate.parse("2027-02-01"));
        assertThat(newSnapshot.getValue())
                .containsEntry("name", "Revised Festival")
                .containsEntry("endDate", LocalDate.parse("2027-02-03"));
    }

    @Test
    void checksProgrammerAuthorizationBeforeUpdateIdempotencyOrMutation() {
        org.mockito.Mockito.doThrow(new ForbiddenException())
                .when(authorization).requireProgrammer(PROGRAM_ID);
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setDescription("Updated");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 0, patch, "key"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
        verify(programRepository, never()).saveAndFlush(any());
    }

    @Test
    void replaysUpdateAndRejectsAnIdempotencyPayloadMismatchWithoutMutation() {
        ProgramDetailResponse stored = new ProgramDetailResponse(
                PROGRAM_ID, "Revised", "Description", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-01"), ProgramState.CREATED, NOW, 4,
                new UserSummaryResponse(ACTOR_ID, "alice", "Alice Programmer"));
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("PROGRAM.UPDATE"), eq("replay-key"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));
        ProgramUpdateRequest replayPatch = new ProgramUpdateRequest();
        replayPatch.setName("Revised");

        ProgramCommandResult<ProgramDetailResponse> replay =
                service.update(PROGRAM_ID, 3, replayPatch, "replay-key");

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.body()).isEqualTo(stored);
        verify(programRepository, never()).findById(any());

        when(idempotencyManager.execute(eq("PROGRAM.UPDATE"), eq("mismatch-key"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        ProgramUpdateRequest mismatchPatch = new ProgramUpdateRequest();
        mismatchPatch.setDescription("Different");
        assertThatThrownBy(() -> service.update(
                PROGRAM_ID, 3, mismatchPatch, "mismatch-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsEmptyUpdateAndRevalidatesResultingDateRange() {
        assertThatThrownBy(() -> service.update(
                PROGRAM_ID, 0, new ProgramUpdateRequest(), "key"))
                .isInstanceOf(InvalidInputException.class)
                .extracting(error -> ((InvalidInputException) error).errorCode())
                .isEqualTo("EMPTY_PROGRAM_UPDATE");

        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        ProgramUpdateRequest invalid = new ProgramUpdateRequest();
        invalid.setStartDate(LocalDate.parse("2027-03-01"));
        assertThatThrownBy(() -> service.update(PROGRAM_ID, 0, invalid, "key-2"))
                .isInstanceOf(InvalidInputException.class)
                .extracting(error -> ((InvalidInputException) error).errorCode())
                .isEqualTo("INVALID_DATE_RANGE");
    }

    @Test
    void rejectsAnnouncedUpdate() {
        when(programRepository.findById(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.ANNOUNCED, 2)));
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setDescription("New description");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 2, patch, "key"))
                .isInstanceOf(InvalidStateException.class);
        verify(programRepository, never()).saveAndFlush(any());
    }

    @Test
    void preservesCaseInsensitiveNameUniquenessOnUpdate() {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(programRepository.existsByNameIgnoreCaseAndIdNot("festival two", PROGRAM_ID))
                .thenReturn(true);
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setName("festival two");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 0, patch, "key"))
                .isInstanceOf(ProgramNameExistsException.class);
        verify(programRepository, never()).saveAndFlush(program);
    }

    @Test
    void translatesDatabaseNameRaceDuringUpdateToStableConflict() {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(programRepository.existsByNameIgnoreCaseAndIdNot("Festival Two", PROGRAM_ID))
                .thenReturn(false);
        when(programRepository.saveAndFlush(program)).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("SQL uk_program_name"));
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setName("Festival Two");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 0, patch, "race-key"))
                .isInstanceOf(ProgramNameExistsException.class)
                .hasMessageNotContaining("SQL");
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsStaleVersionAndPropagatesOptimisticLockRace() {
        ProgramEntity stale = program(ProgramState.CREATED, 3);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(stale));
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setDescription("New description");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 2, patch, "key"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);

        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(stale));
        when(programRepository.saveAndFlush(stale)).thenThrow(
                new ObjectOptimisticLockingFailureException(ProgramEntity.class, PROGRAM_ID));
        assertThatThrownBy(() -> service.update(PROGRAM_ID, 3, patch, "key-2"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    @Test
    void addsProgrammerAndStaffInCreatedWithAssignmentMetadataAndVersionBump() {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(programRepository.incrementVersion(PROGRAM_ID, 0)).thenReturn(1);

        for (ProgramRoleType role : new ProgramRoleType[] {
                ProgramRoleType.PROGRAMMER, ProgramRoleType.STAFF }) {
            encodedResponse.set(null);
            ProgramCommandResult<ProgramRoleResponse> result = service.addRole(
                    PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, role), "key-" + role);
            assertThat(result.body().role()).isEqualTo(role);
            assertThat(result.body().assignedAt()).isEqualTo(NOW);
            assertThat(result.body().assignedByUserId()).isEqualTo(ACTOR_ID);
            assertThat(result.body().programVersion()).isEqualTo(1);
        }
        verify(programRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(PROGRAM_ID);
        verify(auditLoggingService, org.mockito.Mockito.times(2)).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_ROLE_ADDED"), eq("PROGRAM_ROLE"), eq(PROGRAM_ID),
                any(), any(), eq(null));
    }

    @Test
    void allowsProgrammerAdditionUntilAnnouncedButStaffOnlyInCreated() {
        ProgramEntity submission = program(ProgramState.SUBMISSION, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(submission));

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, ProgramRoleType.STAFF), "key"))
                .isInstanceOf(InvalidStateException.class);

        ProgramEntity announced = program(ProgramState.ANNOUNCED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(announced));
        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER), "key-2"))
                .isInstanceOf(InvalidStateException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "PROGRAMMER,STAFF",
            "PROGRAMMER,SUBMITTER",
            "STAFF,PROGRAMMER",
            "STAFF,SUBMITTER",
            "SUBMITTER,PROGRAMMER",
            "SUBMITTER,STAFF"
    })
    void rejectsEveryMutuallyExclusiveRoleCombination(
            ProgramRoleType existingRole,
            ProgramRoleType requestedRole) {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        ProgramRoleEntity existing = new ProgramRoleEntity(program, target, existingRole, NOW, actor);
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(existing));

        if (requestedRole == ProgramRoleType.SUBMITTER) {
            assertThatThrownBy(() -> service.addRole(
                    PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, requestedRole), "key"))
                    .isInstanceOf(InvalidInputException.class);
        } else {
            assertThatThrownBy(() -> service.addRole(
                    PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, requestedRole), "key"))
                    .isInstanceOf(RoleConflictException.class);
        }
    }

    @Test
    void rejectsDuplicateRoleAndUnknownRegisteredUser() {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(program, target, ProgramRoleType.STAFF, NOW, actor)));

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, ProgramRoleType.STAFF), "key"))
                .isInstanceOf(ProgramRoleExistsException.class);

        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.empty());
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0, new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER), "key-2"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void replaysStoredRoleAdditionWithoutASecondAssignment() {
        ProgramRoleResponse stored = new ProgramRoleResponse(
                PROGRAM_ID, TARGET_ID, "Bob Target", ProgramRoleType.PROGRAMMER,
                NOW, ACTOR_ID, 2);
        encodedResponse.set(stored);
        when(idempotencyManager.execute(eq("PROGRAM.ROLE.ADD"), eq("role-key"), any(), any()))
                .thenReturn(new IdempotencyResult(201, "{\"stored\":true}", true));

        ProgramCommandResult<ProgramRoleResponse> result = service.addRole(
                PROGRAM_ID, 1,
                new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER),
                "role-key");

        assertThat(result.replayed()).isTrue();
        assertThat(result.body()).isEqualTo(stored);
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(roleRepository, never()).saveAndFlush(any());
    }

    @Test
    void rejectsRoleAdditionPayloadMismatchAndDatabaseRaceWithoutLeakingDetails() {
        when(idempotencyManager.execute(eq("PROGRAM.ROLE.ADD"), eq("mismatch-key"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 1,
                new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER),
                "mismatch-key"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).findByIdForUpdate(any());

        ProgramEntity program = program(ProgramState.CREATED, 1);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(roleRepository.saveAndFlush(any())).thenThrow(
                new org.springframework.dao.DataIntegrityViolationException("SQL pk_program_role"));

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 1,
                new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER),
                "race-key"))
                .isInstanceOf(RoleConflictException.class)
                .hasMessageNotContaining("SQL");
        verify(programRepository, never()).incrementVersion(PROGRAM_ID, 1);
    }

    @Test
    void checksProgrammerAuthorizationOnEveryManagementCommandBeforeMutation() {
        ProgramEntity program = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        org.mockito.Mockito.doThrow(new ForbiddenException())
                .when(authorization).requireProgrammer(PROGRAM_ID);

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0,
                new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER), "add-key"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.delete(PROGRAM_ID, 0))
                .isInstanceOf(ForbiddenException.class);

        verify(roleRepository, never()).saveAndFlush(any());
        verify(roleRepository, never()).delete(any());
        verify(programRepository, never()).delete(any());
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsCreatorRemovalAndPermitsNonCreatorRoleRemovalsInTheirAllowedStates() {
        ProgramEntity created = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(roleRepository.findRole(PROGRAM_ID, ACTOR_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(created, actor, ProgramRoleType.PROGRAMMER, NOW, actor)));
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, ACTOR_ID, 0))
                .isInstanceOf(CreatorRoleRequiredException.class);

        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(created, target, ProgramRoleType.STAFF, NOW, actor)));
        when(programRepository.incrementVersion(PROGRAM_ID, 0)).thenReturn(1);
        assertThat(service.removeRole(PROGRAM_ID, TARGET_ID, 0)).isEqualTo(1);
        verify(roleRepository).delete(any(ProgramRoleEntity.class));
        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_ROLE_REMOVED"), eq("PROGRAM_ROLE"), eq(PROGRAM_ID),
                any(), any(), eq(null));
    }

    @Test
    void forbidsFrozenStaffAndAnnouncedProgrammerRemoval() {
        ProgramEntity submission = program(ProgramState.SUBMISSION, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(submission));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(submission, target, ProgramRoleType.STAFF, NOW, actor)));
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(InvalidStateException.class);

        ProgramEntity announced = program(ProgramState.ANNOUNCED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(announced));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(announced, target, ProgramRoleType.PROGRAMMER, NOW, actor)));
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void removesNonCreatorProgrammerBeforeAnnouncedAndSafelyReportsMissingAssignments() {
        ProgramEntity scheduling = program(ProgramState.SCHEDULING, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(scheduling));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(scheduling, target, ProgramRoleType.PROGRAMMER, NOW, actor)));
        when(programRepository.incrementVersion(PROGRAM_ID, 0)).thenReturn(1);
        assertThat(service.removeRole(PROGRAM_ID, TARGET_ID, 0)).isEqualTo(1);

        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(ProgramRoleNotFoundException.class);
    }

    @Test
    void concealsSubmitterAssignmentFromManagedRoleRemoval() {
        ProgramEntity created = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(created, target, ProgramRoleType.SUBMITTER, NOW, null)));

        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(ProgramRoleNotFoundException.class)
                .extracting(error -> ((ProgramRoleNotFoundException) error).errorCode())
                .isEqualTo("PROGRAM_ROLE_NOT_FOUND");
        verify(roleRepository, never()).delete(any());
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deletesOnlyCreatedProgramAfterWritingSafeAuditSnapshot() {
        ProgramEntity created = program(ProgramState.CREATED, 2);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(roleRepository.countByIdProgramId(PROGRAM_ID)).thenReturn(3L);

        service.delete(PROGRAM_ID, 2);

        verify(auditLoggingService).recordUserAction(
                eq(ACTOR_ID), eq("PROGRAM_DELETED"), eq("PROGRAM"), eq(PROGRAM_ID),
                any(), any(), eq(null));
        verify(programRepository).delete(created);
        verify(programRepository).flush();

        ProgramEntity submission = program(ProgramState.SUBMISSION, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(submission));
        assertThatThrownBy(() -> service.delete(PROGRAM_ID, 0))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void repeatedDeleteUsesSafeNotFoundWithoutAnotherAudit() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(PROGRAM_ID, 0))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
        verify(programRepository, never()).delete(any());
    }

    @Test
    void rejectsStaleDeleteAndDoesNotAuditOrDelete() {
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenReturn(Optional.of(program(ProgramState.CREATED, 3)));

        assertThatThrownBy(() -> service.delete(PROGRAM_ID, 2))
                .isInstanceOf(OptimisticConcurrencyConflictException.class)
                .extracting(error -> ((OptimisticConcurrencyConflictException) error).errorCode())
                .isEqualTo("CONCURRENT_MODIFICATION");
        verify(auditLoggingService, never()).recordUserAction(
                any(), any(), any(), any(), any(), any(), any());
        verify(programRepository, never()).delete(any());
    }

    @Test
    void propagatesAuditFailureSoTransactionalCreationCannotComplete() throws Exception {
        when(programRepository.existsByNameIgnoreCase("Festival 2027")).thenReturn(false);
        when(auditLoggingService.recordUserAction(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit unavailable"));

        assertThatThrownBy(() -> service.create(createRequest("Festival 2027"), "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThat(ProgramManagementService.class
                .getMethod("create", ProgramCreateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    void auditFailurePreventsRoleVersionBumpAndProgramDeletion() {
        ProgramEntity created = program(ProgramState.CREATED, 0);
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(userRepository.findById(TARGET_ID)).thenReturn(Optional.of(target));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.empty());
        when(auditLoggingService.recordUserAction(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit unavailable"));

        assertThatThrownBy(() -> service.addRole(
                PROGRAM_ID, 0,
                new ProgramRoleRequest(TARGET_ID, ProgramRoleType.PROGRAMMER), "role-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(programRepository, never()).incrementVersion(PROGRAM_ID, 0);

        assertThatThrownBy(() -> service.delete(PROGRAM_ID, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        verify(programRepository, never()).delete(created);
    }

    @Test
    void auditFailureAbortsUpdateAndRoleRemovalCommands() {
        ProgramEntity created = program(ProgramState.CREATED, 0);
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(created));
        when(roleRepository.findRole(PROGRAM_ID, TARGET_ID)).thenReturn(Optional.of(
                new ProgramRoleEntity(created, target, ProgramRoleType.STAFF, NOW, actor)));
        when(auditLoggingService.recordUserAction(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit unavailable"));
        ProgramUpdateRequest patch = new ProgramUpdateRequest();
        patch.setDescription("Updated description");

        assertThatThrownBy(() -> service.update(PROGRAM_ID, 0, patch, "update-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");
        assertThatThrownBy(() -> service.removeRole(PROGRAM_ID, TARGET_ID, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("audit unavailable");

        verify(programRepository).saveAndFlush(created);
        verify(roleRepository).delete(any(ProgramRoleEntity.class));
        verify(programRepository, never()).incrementVersion(PROGRAM_ID, 0);
    }

    @Test
    void everyProgramMutationDeclaresATransactionBoundary() throws Exception {
        assertThat(ProgramManagementService.class
                .getMethod("create", ProgramCreateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ProgramManagementService.class
                .getMethod("update", UUID.class, long.class, ProgramUpdateRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ProgramManagementService.class
                .getMethod("addRole", UUID.class, long.class, ProgramRoleRequest.class, String.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ProgramManagementService.class
                .getMethod("removeRole", UUID.class, UUID.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
        assertThat(ProgramManagementService.class
                .getMethod("delete", UUID.class, long.class)
                .isAnnotationPresent(Transactional.class)).isTrue();
    }

    private static ProgramCreateRequest createRequest(String name) {
        return new ProgramCreateRequest(
                name,
                "  Festival description  ",
                LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-01"));
    }

    private static UserEntity user(UUID id, String username, String fullName) {
        return new UserEntity(id, username, "credential", fullName);
    }

    private ProgramEntity program(ProgramState state, long version) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID,
                actor,
                "Festival",
                "Description",
                LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-02-01"),
                NOW);
        set(program, "state", state);
        set(program, "version", version);
        return program;
    }

    private static void set(ProgramEntity program, String fieldName, Object value) {
        try {
            Field field = ProgramEntity.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(program, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
