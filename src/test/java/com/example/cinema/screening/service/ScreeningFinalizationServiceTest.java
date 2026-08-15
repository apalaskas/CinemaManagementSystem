package com.example.cinema.screening.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.math.BigDecimal;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.SchedulingConflictException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.screening.api.ScreeningDecision;
import com.example.cinema.screening.api.ScreeningDecisionRequest;
import com.example.cinema.screening.api.ScreeningDecisionResponse;
import com.example.cinema.screening.api.ScreeningDetailResponse;
import com.example.cinema.screening.api.ScreeningFinalSubmissionRequest;
import com.example.cinema.screening.api.ScreeningScheduleRequest;
import com.example.cinema.screening.api.ScreeningScheduleResponse;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;

import tools.jackson.databind.ObjectMapper;

class ScreeningFinalizationServiceTest {

    private static final UUID PROGRAMMER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID SUBMITTER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID SCREENING_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final Instant NOW = Instant.parse("2027-05-01T09:30:00Z");
    private static final Instant START = Instant.parse("2027-06-01T10:00:00Z");
    private static final Instant END = Instant.parse("2027-06-01T12:00:00Z");

    private final ScreeningRepository screeningRepository = mock(ScreeningRepository.class);
    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ContextAwareAuthorizationService authorization = mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity programmer = user(PROGRAMMER_ID, "programmer", "Programmer Person");
    private final UserEntity submitter = user(SUBMITTER_ID, "submitter", "Submitter Person");
    private final ScreeningFinalizationService service = new ScreeningFinalizationService(
            screeningRepository, programRepository, authorization, idempotencyManager,
            auditLoggingService, objectMapper, clock);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(authorization.currentUser()).thenReturn(identity(PROGRAMMER_ID, "programmer", "Programmer Person"));
        when(screeningRepository.saveAndFlush(any(ScreeningEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenAnswer(invocation -> {
            encodedResponse.set(invocation.getArgument(0));
            return "{\"stored\":true}";
        });
        when(objectMapper.readValue(anyString(), any(Class.class))).thenAnswer(invocation -> {
            Class<Object> type = invocation.getArgument(1);
            return type.cast(encodedResponse.get());
        });
        when(idempotencyManager.execute(anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            Supplier<StoredCommandResponse> command = invocation.getArgument(3);
            StoredCommandResponse response = command.get();
            return new IdempotencyResult(response.status(), response.body(), false);
        });
    }

    @ParameterizedTest
    @MethodSource("approvalNotes")
    void programmerApprovesReviewedScreeningDuringSchedulingWithOptionalNotes(
            String requestedNotes, String storedNotes) {
        ProgramEntity program = program(ProgramState.SCHEDULING);
        ScreeningEntity screening = screening(program, ScreeningState.REVIEWED, null, 3);
        allowProgrammer(program, screening);

        ScreeningCommandResult<ScreeningDecisionResponse> result = service.decide(
                SCREENING_ID, 3,
                new ScreeningDecisionRequest(ScreeningDecision.APPROVE, requestedNotes, null),
                "approve-key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().decision()).isEqualTo(ScreeningDecision.APPROVE);
        assertThat(result.body().state()).isEqualTo(ScreeningState.APPROVED);
        assertThat(result.body().conditionalNotes()).isEqualTo(storedNotes);
        assertThat(result.body().rejectionReason()).isNull();
        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
        verify(authorization, org.mockito.Mockito.times(2)).requireProgrammer(PROGRAM_ID);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> oldSnapshot = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newSnapshot = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(PROGRAMMER_ID), eq("SCREENING_DECIDED"), eq("SCREENING"), eq(SCREENING_ID),
                oldSnapshot.capture(), newSnapshot.capture(), eq(null));
        assertThat(oldSnapshot.getValue()).containsEntry("state", ScreeningState.REVIEWED);
        assertThat(newSnapshot.getValue())
                .containsEntry("state", ScreeningState.APPROVED)
                .containsEntry("conditionalNotes", storedNotes);
    }

    @ParameterizedTest
    @MethodSource("validRejections")
    void programmerRejectsReviewedOrFinallySubmittedApprovedScreening(
            ProgramState programState, ScreeningState screeningState, Instant finalSubmittedAt) {
        ProgramEntity program = program(programState);
        ScreeningEntity screening = screening(program, screeningState, finalSubmittedAt, 0);
        allowProgrammer(program, screening);

        ScreeningCommandResult<ScreeningDecisionResponse> result = service.decide(
                SCREENING_ID, 0,
                new ScreeningDecisionRequest(ScreeningDecision.REJECT, null, "  Not acceptable  "),
                "reject-key");

        assertThat(result.body().state()).isEqualTo(ScreeningState.REJECTED);
        assertThat(result.body().rejectionReason()).isEqualTo("Not acceptable");
        assertThat(screening.getState()).isEqualTo(ScreeningState.REJECTED);
        verify(auditLoggingService).recordUserAction(
                eq(PROGRAMMER_ID), eq("SCREENING_DECIDED"), eq("SCREENING"), eq(SCREENING_ID),
                any(), any(), eq("Not acceptable"));
    }

    @ParameterizedTest
    @MethodSource("invalidDecisionBodies")
    void decisionRequiresValidDecisionSpecificReasonAndNotes(
            ScreeningDecision decision, String notes, String reason, String field) {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        assertThatThrownBy(() -> service.decide(
                SCREENING_ID, 0, new ScreeningDecisionRequest(decision, notes, reason), "invalid"))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors()).extracting(error -> error.field()).contains(field));
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidDecisionStates")
    void rejectsDecisionInInvalidProgramPhaseOrScreeningState(
            ProgramState programState, ScreeningState screeningState, ScreeningDecision decision) {
        ProgramEntity program = program(programState);
        ScreeningEntity screening = screening(
                program, screeningState,
                screeningState == ScreeningState.APPROVED ? NOW : null, 0);
        allowProgrammer(program, screening);
        ScreeningDecisionRequest request = decision == ScreeningDecision.REJECT
                ? new ScreeningDecisionRequest(decision, null, "Reason")
                : new ScreeningDecisionRequest(decision, null, null);

        assertThatThrownBy(() -> service.decide(SCREENING_ID, 0, request, "invalid-state"))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void decisionPhaseManualRejectionRequiresPriorFinalSubmission() {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 0);
        allowProgrammer(program, screening);
        assertThatThrownBy(() -> service.decide(
                SCREENING_ID, 0,
                new ScreeningDecisionRequest(ScreeningDecision.REJECT, null, "Reason"),
                "reject-without-final"))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void decisionRequiresProgrammerBeforeIdempotencyAndAgainAfterProgramLock() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        doThrow(new ForbiddenException()).when(authorization).requireProgrammer(PROGRAM_ID);
        assertThatThrownBy(() -> service.decide(
                SCREENING_ID, 0, approve(), "forbidden"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());

        org.mockito.Mockito.reset(authorization);
        when(authorization.currentUser()).thenReturn(identity(PROGRAMMER_ID, "programmer", "Programmer Person"));
        ProgramEntity program = program(ProgramState.SCHEDULING);
        ScreeningEntity screening = screening(program, ScreeningState.REVIEWED, null, 0);
        allowProgrammer(program, screening);
        doNothing().doThrow(new ForbiddenException()).when(authorization).requireProgrammer(PROGRAM_ID);
        assertThatThrownBy(() -> service.decide(
                SCREENING_ID, 0, approve(), "changed-role"))
                .isInstanceOf(ForbiddenException.class);
        verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void ownerFinallySubmitsChangedCompleteContentOnceWithoutConflictCheck() {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 5);
        allowOwner(program, screening);
        ScreeningFinalSubmissionRequest request = finalChanges();

        ScreeningCommandResult<ScreeningDetailResponse> result = service.finalSubmit(
                SCREENING_ID, 5, request, "final-key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().state()).isEqualTo(ScreeningState.APPROVED);
        assertThat(result.body().filmTitle()).isEqualTo("Final Film");
        assertThat(result.body().candidateAuditoriumName()).isEqualTo("New Candidate");
        assertThat(result.body().finalSubmittedAt()).isEqualTo(NOW);
        assertThat(screening.getFinalSubmittedAt()).isEqualTo(NOW);
        verify(authorization, org.mockito.Mockito.times(2)).requireOwner(screening);
        verify(authorization, org.mockito.Mockito.times(2)).requireSubmitter(PROGRAM_ID);
        verify(screeningRepository, never()).findSchedulingConflictsForUpdate(any(), any(), any(), any());
        verify(auditLoggingService).recordUserAction(
                eq(SUBMITTER_ID), eq("SCREENING_FINAL_SUBMITTED"), eq("SCREENING"), eq(SCREENING_ID),
                any(), any(), eq(null));
    }

    @Test
    void emptyFinalSubmissionFinalizesExistingCompleteContent() {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 0);
        allowOwner(program, screening);

        ScreeningCommandResult<ScreeningDetailResponse> result = service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "unchanged-final");
        assertThat(result.body().filmTitle()).isEqualTo("Film");
        assertThat(result.body().finalSubmittedAt()).isEqualTo(NOW);
    }

    @Test
    void finalSubmissionRejectsNonOwnerBeforeIdempotencyAndAfterLock() {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        doThrow(new ForbiddenException()).when(authorization).requireOwner(screening);
        assertThatThrownBy(() -> service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "wrong-owner"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());

        org.mockito.Mockito.reset(authorization);
        when(authorization.currentUser()).thenReturn(identity(SUBMITTER_ID, "submitter", "Submitter Person"));
        allowOwner(program, screening);
        doNothing().doThrow(new ForbiddenException()).when(authorization).requireOwner(screening);
        assertThatThrownBy(() -> service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "owner-changed"))
                .isInstanceOf(ForbiddenException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @MethodSource("missingFinalFields")
    void finalSubmissionRevalidatesEveryMandatoryResultField(String fieldName, String apiField) {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 0);
        set(screening, fieldName, null);
        allowOwner(program, screening);

        assertThatThrownBy(() -> service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "missing-" + apiField))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors()).extracting(error -> error.field()).contains(apiField));
        assertThat(screening.getFinalSubmittedAt()).isNull();
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidFinalChanges")
    void finalSubmissionRejectsInvalidSuppliedContent(ScreeningFinalSubmissionRequest request, String field) {
        ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, null, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        asSubmitter();
        assertThatThrownBy(() -> service.finalSubmit(SCREENING_ID, 0, request, "invalid-final"))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors()).extracting(error -> error.field()).contains(field));
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @Test
    void finalSubmissionRequiresFinalPublicationApprovedAndNoPriorSubmission() {
        ProgramEntity wrongPhase = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(wrongPhase, ScreeningState.APPROVED, null, 0);
        allowOwner(wrongPhase, screening);
        assertFinalStateConflict(screening, "wrong-phase");

        ProgramEntity finalPublication = program(ProgramState.FINAL_PUBLICATION);
        ScreeningEntity reviewed = screening(finalPublication, ScreeningState.REVIEWED, null, 0);
        allowOwner(finalPublication, reviewed);
        assertFinalStateConflict(reviewed, "wrong-state");

        ScreeningEntity alreadyFinal = screening(finalPublication, ScreeningState.APPROVED, NOW, 0);
        allowOwner(finalPublication, alreadyFinal);
        assertFinalStateConflict(alreadyFinal, "already-final");
    }

    @Test
    void programmerSchedulesFinallySubmittedScreeningWithDifferentFinalAuditorium() {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, NOW, 7);
        allowProgrammer(program, screening);
        when(screeningRepository.findSchedulingConflictsForUpdate(
                SCREENING_ID, "Final Hall", START, END)).thenReturn(List.of());

        ScreeningCommandResult<ScreeningScheduleResponse> result = service.schedule(
                SCREENING_ID, 7, new ScreeningScheduleRequest("  Final Hall  ", START, END), "schedule-key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().state()).isEqualTo(ScreeningState.SCHEDULED);
        assertThat(result.body().finalAuditoriumName()).isEqualTo("Final Hall");
        assertThat(result.body().finalAuditoriumName()).isNotEqualTo(screening.getCandidateAuditoriumName());
        assertThat(screening.getState()).isEqualTo(ScreeningState.SCHEDULED);
        InOrder order = inOrder(programRepository, screeningRepository, auditLoggingService);
        order.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        order.verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        order.verify(screeningRepository).findSchedulingConflictsForUpdate(
                SCREENING_ID, "Final Hall", START, END);
        order.verify(screeningRepository).saveAndFlush(screening);
        order.verify(auditLoggingService).recordUserAction(
                eq(PROGRAMMER_ID), eq("SCREENING_SCHEDULED"), eq("SCREENING"), eq(SCREENING_ID),
                any(), any(), eq(null));
    }

    @ParameterizedTest
    @MethodSource("overlappingIntervals")
    void everyOverlapShapeAndCaseInsensitiveAuditoriumMatchReturnsSafeConflict(
            Instant existingStart, Instant existingEnd, String requestedAuditorium) {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, NOW, 0);
        ScreeningEntity conflict = screening(
                program, ScreeningState.SCHEDULED, NOW, 0, UUID.randomUUID(), existingStart, existingEnd);
        set(conflict, "finalAuditoriumName", "main hall");
        allowProgrammer(program, screening);
        when(screeningRepository.findSchedulingConflictsForUpdate(
                SCREENING_ID, requestedAuditorium, START, END)).thenReturn(List.of(conflict));

        assertThatThrownBy(() -> service.schedule(
                SCREENING_ID, 0,
                new ScreeningScheduleRequest(requestedAuditorium, START, END), "overlap"))
                .isInstanceOf(SchedulingConflictException.class);
        assertThat(screening.getState()).isEqualTo(ScreeningState.APPROVED);
        verify(screeningRepository, never()).saveAndFlush(any());
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void adjacentIntervalsAreAcceptedWhenTheConflictQueryReturnsNoRows() {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, NOW, 0);
        allowProgrammer(program, screening);
        when(screeningRepository.findSchedulingConflictsForUpdate(
                SCREENING_ID, "Main Hall", START, END)).thenReturn(List.of());

        ScreeningCommandResult<ScreeningScheduleResponse> result = service.schedule(
                SCREENING_ID, 0,
                new ScreeningScheduleRequest("Main Hall", START, END), "adjacent-safe");
        assertThat(result.body().state()).isEqualTo(ScreeningState.SCHEDULED);
    }

    @ParameterizedTest
    @MethodSource("invalidScheduleStates")
    void schedulingRequiresDecisionApprovedAndFinalSubmission(
            ProgramState programState, ScreeningState state, Instant finalSubmittedAt) {
        ProgramEntity program = program(programState);
        ScreeningEntity screening = screening(program, state, finalSubmittedAt, 0);
        allowProgrammer(program, screening);
        assertThatThrownBy(() -> service.schedule(
                SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END), "invalid-schedule"))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).findSchedulingConflictsForUpdate(any(), any(), any(), any());
    }

    @Test
    void scheduleValidatesDurationAndIntervalBeforeConflictQuery() {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, NOW, 0);
        allowProgrammer(program, screening);
        Instant tooShort = START.plusSeconds(60);
        assertThatThrownBy(() -> service.schedule(
                SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, tooShort), "too-short"))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors()).extracting(error -> error.field()).contains("endTime"));
        verify(screeningRepository, never()).findSchedulingConflictsForUpdate(any(), any(), any(), any());
    }

    @Test
    void finalStatesAreImmutableAcrossAllThreeCommands() {
        for (ScreeningState finalState : List.of(ScreeningState.SCHEDULED, ScreeningState.REJECTED)) {
            ProgramEntity program = program(ProgramState.DECISION);
            ScreeningEntity screening = screening(program, finalState, NOW, 0);
            allowProgrammer(program, screening);
            assertThatThrownBy(() -> service.decide(
                    SCREENING_ID, 0,
                    new ScreeningDecisionRequest(ScreeningDecision.REJECT, null, "Reason"),
                    "final-decision-" + finalState))
                    .isInstanceOf(InvalidStateException.class);

            allowProgrammer(program, screening);
            assertThatThrownBy(() -> service.schedule(
                    SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END),
                    "final-schedule-" + finalState))
                    .isInstanceOf(InvalidStateException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "final", "schedule"})
    void exactIdempotencyReplaySkipsLocksAndReturnsStoredResponse(String operation) throws Exception {
        Object stored = storedResponse(operation);
        encodedResponse.set(stored);
        if (operation.equals("final")) {
            ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
            ScreeningEntity visible = screening(program, ScreeningState.APPROVED, null, 2);
            when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(visible));
            asSubmitter();
        } else {
            when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        }
        when(idempotencyManager.execute(anyString(), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));

        ScreeningCommandResult<?> result = switch (operation) {
            case "decision" -> service.decide(SCREENING_ID, 2, approve(), "replay");
            case "final" -> service.finalSubmit(
                    SCREENING_ID, 2, new ScreeningFinalSubmissionRequest(), "replay");
            case "schedule" -> service.schedule(
                    SCREENING_ID, 2, new ScreeningScheduleRequest("Hall", START, END), "replay");
            default -> throw new AssertionError(operation);
        };
        assertThat(result.replayed()).isTrue();
        assertThat(result.body()).isEqualTo(stored);
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "final", "schedule"})
    void idempotencyPayloadMismatchReturnsConflictBeforeLocks(String operation) {
        if (operation.equals("final")) {
            ProgramEntity program = program(ProgramState.FINAL_PUBLICATION);
            ScreeningEntity visible = screening(program, ScreeningState.APPROVED, null, 0);
            when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(visible));
            asSubmitter();
        } else {
            when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        }
        when(idempotencyManager.execute(anyString(), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));

        assertThatThrownBy(() -> {
            switch (operation) {
                case "decision" -> service.decide(SCREENING_ID, 0, approve(), "mismatch");
                case "final" -> service.finalSubmit(
                        SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "mismatch");
                case "schedule" -> service.schedule(
                        SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END), "mismatch");
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void schedulingUsesSerializableIsolationAndPropagatesConcurrentLockConflict() throws Exception {
        Transactional annotation = ScreeningFinalizationService.class.getMethod(
                "schedule", UUID.class, long.class, ScreeningScheduleRequest.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(annotation.isolation()).isEqualTo(Isolation.SERIALIZABLE);

        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenThrow(new PessimisticLockingFailureException("concurrent schedule"));
        assertThatThrownBy(() -> service.schedule(
                SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END), "race"))
                .isInstanceOf(PessimisticLockingFailureException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void optimisticConflictPreventsEveryWorkflowWrite() {
        ProgramEntity program = program(ProgramState.SCHEDULING);
        ScreeningEntity screening = screening(program, ScreeningState.REVIEWED, null, 4);
        allowProgrammer(program, screening);
        assertThatThrownBy(() -> service.decide(SCREENING_ID, 3, approve(), "stale"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"decision", "final", "schedule"})
    void auditFailureRollsBackEveryWorkflowMutation(String operation) {
        ProgramEntity program = switch (operation) {
            case "decision" -> program(ProgramState.SCHEDULING);
            case "final" -> program(ProgramState.FINAL_PUBLICATION);
            case "schedule" -> program(ProgramState.DECISION);
            default -> throw new AssertionError(operation);
        };
        ScreeningEntity screening = switch (operation) {
            case "decision" -> screening(program, ScreeningState.REVIEWED, null, 0);
            case "final" -> screening(program, ScreeningState.APPROVED, null, 0);
            case "schedule" -> screening(program, ScreeningState.APPROVED, NOW, 0);
            default -> throw new AssertionError(operation);
        };
        if (operation.equals("final")) {
            allowOwner(program, screening);
        } else {
            allowProgrammer(program, screening);
        }
        when(screeningRepository.findSchedulingConflictsForUpdate(any(), any(), any(), any()))
                .thenReturn(List.of());
        doThrow(new IllegalStateException("audit unavailable")).when(auditLoggingService)
                .recordUserAction(any(), any(), any(), any(), any(), any(), any());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        ScreeningFinalizationService proxy = (ScreeningFinalizationService) factory.getProxy();

        assertThatThrownBy(() -> {
            switch (operation) {
                case "decision" -> proxy.decide(SCREENING_ID, 0, approve(), "rollback-decision");
                case "final" -> proxy.finalSubmit(
                        SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "rollback-final");
                case "schedule" -> proxy.schedule(
                        SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END),
                        "rollback-schedule");
                default -> throw new AssertionError(operation);
            }
        }).isInstanceOf(IllegalStateException.class);
        verify(transactionManager).rollback(status);
        verify(transactionManager, never()).commit(status);
    }

    @Test
    void idempotencyCompletionFailureRollsBackSchedulingAfterAudit() {
        ProgramEntity program = program(ProgramState.DECISION);
        ScreeningEntity screening = screening(program, ScreeningState.APPROVED, NOW, 0);
        allowProgrammer(program, screening);
        when(screeningRepository.findSchedulingConflictsForUpdate(any(), any(), any(), any()))
                .thenReturn(List.of());
        doAnswer(invocation -> {
            Supplier<StoredCommandResponse> command = invocation.getArgument(3);
            command.get();
            throw new IllegalStateException("idempotency completion failed");
        }).when(idempotencyManager).execute(anyString(), anyString(), any(), any());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        ScreeningFinalizationService proxy = (ScreeningFinalizationService) factory.getProxy();

        assertThatThrownBy(() -> proxy.schedule(
                SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END), "idem-failure"))
                .isInstanceOf(IllegalStateException.class);
        verify(auditLoggingService).recordUserAction(any(), any(), any(), any(), any(), any(), any());
        verify(transactionManager).rollback(status);
    }

    @Test
    void unknownOrDeletedScreeningIsConcealedForEveryWorkflow() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.empty());
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.decide(SCREENING_ID, 0, approve(), "missing-decision"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), "missing-final"))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.schedule(
                SCREENING_ID, 0, new ScreeningScheduleRequest("Hall", START, END), "missing-schedule"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private void assertFinalStateConflict(ScreeningEntity screening, String key) {
        assertThatThrownBy(() -> service.finalSubmit(
                SCREENING_ID, 0, new ScreeningFinalSubmissionRequest(), key))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(screening);
    }

    private void allowProgrammer(ProgramEntity program, ScreeningEntity screening) {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
    }

    private void allowOwner(ProgramEntity program, ScreeningEntity screening) {
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
        asSubmitter();
    }

    private void asSubmitter() {
        when(authorization.currentUser()).thenReturn(identity(SUBMITTER_ID, "submitter", "Submitter Person"));
    }

    private ProgramEntity program(ProgramState state) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID, programmer, "Program", "Description",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), NOW);
        set(program, "state", state);
        return program;
    }

    private ScreeningEntity screening(
            ProgramEntity program, ScreeningState state, Instant finalSubmittedAt, long version) {
        return screening(program, state, finalSubmittedAt, version, SCREENING_ID, START, END);
    }

    private ScreeningEntity screening(
            ProgramEntity program,
            ScreeningState state,
            Instant finalSubmittedAt,
            long version,
            UUID id,
            Instant start,
            Instant end) {
        ScreeningEntity screening = new ScreeningEntity(
                id, program, submitter, "Film", "Cast", "Drama", 90,
                "Candidate Hall", start, end, NOW);
        set(screening, "state", state);
        set(screening, "finalSubmittedAt", finalSubmittedAt);
        set(screening, "version", version);
        return screening;
    }

    private static ScreeningDecisionRequest approve() {
        return new ScreeningDecisionRequest(ScreeningDecision.APPROVE, null, null);
    }

    private static ScreeningFinalSubmissionRequest finalChanges() {
        ScreeningFinalSubmissionRequest request = new ScreeningFinalSubmissionRequest();
        request.setFilmTitle("  Final Film  ");
        request.setCast("Final Cast");
        request.setGenre("Documentary");
        request.setDurationMinutes(100);
        request.setCandidateAuditoriumName("  New Candidate  ");
        request.setStartTime(START);
        request.setEndTime(END);
        return request;
    }

    private Object storedResponse(String operation) {
        return switch (operation) {
            case "decision" -> new ScreeningDecisionResponse(
                    SCREENING_ID, ScreeningDecision.APPROVE, ScreeningState.APPROVED,
                    null, null, 3);
            case "final" -> detailResponse(ScreeningState.APPROVED, NOW, 3);
            case "schedule" -> new ScreeningScheduleResponse(
                    SCREENING_ID, ScreeningState.SCHEDULED, "Hall", START, END, 3);
            default -> throw new AssertionError(operation);
        };
    }

    private ScreeningDetailResponse detailResponse(
            ScreeningState state, Instant finalSubmittedAt, long version) {
        return new ScreeningDetailResponse(
                SCREENING_ID, PROGRAM_ID, "Film", "Cast", "Drama", 90,
                "Candidate Hall", null, START, END, state, null, finalSubmittedAt, null,
                new com.example.cinema.program.api.UserSummaryResponse(
                        SUBMITTER_ID, "submitter", "Submitter Person"),
                null, NOW, version);
    }

    private static UserEntity user(UUID id, String username, String fullName) {
        return new UserEntity(id, username, "hash-never-exposed", fullName);
    }

    private static AuthenticatedUserIdentity identity(UUID id, String username, String fullName) {
        return new AuthenticatedUserIdentity(id, username, fullName);
    }

    private static Stream<Arguments> approvalNotes() {
        return Stream.of(
                Arguments.of(null, null),
                Arguments.of("   ", null),
                Arguments.of("  Requested changes  ", "Requested changes"));
    }

    private static Stream<Arguments> validRejections() {
        return Stream.of(
                Arguments.of(ProgramState.SCHEDULING, ScreeningState.REVIEWED, null),
                Arguments.of(ProgramState.DECISION, ScreeningState.APPROVED, NOW));
    }

    private static Stream<Arguments> invalidDecisionBodies() {
        return Stream.of(
                Arguments.of(null, null, null, "decision"),
                Arguments.of(ScreeningDecision.REJECT, null, null, "reason"),
                Arguments.of(ScreeningDecision.REJECT, null, "   ", "reason"),
                Arguments.of(ScreeningDecision.REJECT, "notes", "reason", "conditionalNotes"),
                Arguments.of(ScreeningDecision.APPROVE, null, "reason", "reason"));
    }

    private static Stream<Arguments> invalidDecisionStates() {
        Stream<Arguments> wrongSchedulingStates = Stream.of(ScreeningState.values())
                .filter(state -> state != ScreeningState.REVIEWED)
                .map(state -> Arguments.of(
                        ProgramState.SCHEDULING, state, ScreeningDecision.APPROVE));
        Stream<Arguments> wrongPhases = Stream.of(ProgramState.values())
                .filter(state -> state != ProgramState.SCHEDULING && state != ProgramState.DECISION)
                .map(state -> Arguments.of(state, ScreeningState.REVIEWED, ScreeningDecision.APPROVE));
        Stream<Arguments> decisionApproval = Stream.of(
                Arguments.of(ProgramState.DECISION, ScreeningState.APPROVED, ScreeningDecision.APPROVE));
        return Stream.concat(wrongSchedulingStates, Stream.concat(wrongPhases, decisionApproval));
    }

    private static Stream<Arguments> missingFinalFields() {
        return Stream.of(
                Arguments.of("filmTitle", "filmTitle"),
                Arguments.of("castText", "cast"),
                Arguments.of("genre", "genre"),
                Arguments.of("durationMinutes", "durationMinutes"),
                Arguments.of("candidateAuditoriumName", "candidateAuditoriumName"),
                Arguments.of("startTime", "startTime"),
                Arguments.of("endTime", "endTime"));
    }

    private static Stream<Arguments> invalidFinalChanges() {
        ScreeningFinalSubmissionRequest blankTitle = new ScreeningFinalSubmissionRequest();
        blankTitle.setFilmTitle("  ");
        ScreeningFinalSubmissionRequest zeroDuration = new ScreeningFinalSubmissionRequest();
        zeroDuration.setDurationMinutes(0);
        ScreeningFinalSubmissionRequest nullStart = new ScreeningFinalSubmissionRequest();
        nullStart.setStartTime(null);
        return Stream.of(
                Arguments.of(blankTitle, "filmTitle"),
                Arguments.of(zeroDuration, "durationMinutes"),
                Arguments.of(nullStart, "startTime"));
    }

    private static Stream<Arguments> overlappingIntervals() {
        return Stream.of(
                Arguments.of(START, END, "Main Hall"),
                Arguments.of(START.minusSeconds(3600), END.plusSeconds(3600), "Main Hall"),
                Arguments.of(START.plusSeconds(1800), END.plusSeconds(1800), "Main Hall"),
                Arguments.of(START.minusSeconds(1800), END.minusSeconds(1800), "MAIN HALL"));
    }

    private static Stream<Arguments> invalidScheduleStates() {
        return Stream.of(
                Arguments.of(ProgramState.SCHEDULING, ScreeningState.APPROVED, NOW),
                Arguments.of(ProgramState.FINAL_PUBLICATION, ScreeningState.APPROVED, NOW),
                Arguments.of(ProgramState.DECISION, ScreeningState.REVIEWED, NOW),
                Arguments.of(ProgramState.DECISION, ScreeningState.APPROVED, null),
                Arguments.of(ProgramState.DECISION, ScreeningState.SCHEDULED, NOW),
                Arguments.of(ProgramState.DECISION, ScreeningState.REJECTED, NOW));
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
