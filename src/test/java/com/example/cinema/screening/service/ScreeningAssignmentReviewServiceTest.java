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

import java.math.BigDecimal;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.IdempotencyConflictException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.ReviewAlreadyExistsException;
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
import com.example.cinema.screening.api.ScreeningHandlerAssignmentRequest;
import com.example.cinema.screening.api.ScreeningHandlerAssignmentResponse;
import com.example.cinema.screening.api.ScreeningReviewRequest;
import com.example.cinema.screening.api.ScreeningReviewResponse;
import com.example.cinema.screening.domain.ReviewEntity;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ReviewRepository;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authorization.ContextAwareAuthorizationService;
import com.example.cinema.user.domain.UserEntity;
import com.example.cinema.user.repository.UserRepository;

import tools.jackson.databind.ObjectMapper;

class ScreeningAssignmentReviewServiceTest {

    private static final UUID PROGRAMMER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID STAFF_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OTHER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID PROGRAM_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID SCREENING_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2027-03-01T10:15:30Z");

    private final ScreeningRepository screeningRepository = mock(ScreeningRepository.class);
    private final ReviewRepository reviewRepository = mock(ReviewRepository.class);
    private final ProgramRepository programRepository = mock(ProgramRepository.class);
    private final ProgramRoleRepository roleRepository = mock(ProgramRoleRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final ContextAwareAuthorizationService authorization = mock(ContextAwareAuthorizationService.class);
    private final IdempotencyManager idempotencyManager = mock(IdempotencyManager.class);
    private final AuditLoggingService auditLoggingService = mock(AuditLoggingService.class);
    private final ObjectMapper objectMapper = mock(ObjectMapper.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<Object> encodedResponse = new AtomicReference<>();
    private final UserEntity programmer = user(PROGRAMMER_ID, "programmer", "Programmer Person");
    private final UserEntity staff = user(STAFF_ID, "staff", "Staff Person");
    private final UserEntity submitter = user(OTHER_ID, "submitter", "Submitter Person");
    private final ScreeningAssignmentReviewService service = new ScreeningAssignmentReviewService(
            screeningRepository, reviewRepository, programRepository, roleRepository, userRepository,
            authorization, idempotencyManager, auditLoggingService, objectMapper, clock);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        when(authorization.currentUser()).thenReturn(identity(PROGRAMMER_ID, "programmer", "Programmer Person"));
        when(screeningRepository.saveAndFlush(any(ScreeningEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reviewRepository.saveAndFlush(any(ReviewEntity.class)))
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

    @Test
    void assignsExactlyOneFrozenStaffHandlerUnderProgramAndScreeningLocks() {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = submitted(program, null, 4);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);

        ScreeningCommandResult<ScreeningHandlerAssignmentResponse> result = service.assignHandler(
                SCREENING_ID, 4, new ScreeningHandlerAssignmentRequest(STAFF_ID), "handler-key");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.body().screeningId()).isEqualTo(SCREENING_ID);
        assertThat(result.body().handler().userId()).isEqualTo(STAFF_ID);
        assertThat(result.body().state()).isEqualTo(ScreeningState.SUBMITTED);
        assertThat(screening.getHandler()).isSameAs(staff);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newSnapshot = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(PROGRAMMER_ID), eq("SCREENING_HANDLER_ASSIGNED"), eq("SCREENING"), eq(SCREENING_ID),
                any(), newSnapshot.capture(), eq(null));
        assertThat(newSnapshot.getValue()).containsEntry("handlerUserId", STAFF_ID);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> canonical = ArgumentCaptor.forClass(Map.class);
        verify(idempotencyManager).execute(
                eq("SCREENING.HANDLER.ASSIGN"), eq("handler-key"), canonical.capture(), any());
        assertThat(canonical.getValue())
                .containsEntry("screeningId", SCREENING_ID)
                .containsEntry("staffUserId", STAFF_ID)
                .containsEntry("expectedVersion", 4L);

        InOrder order = inOrder(programRepository, userRepository, roleRepository,
                screeningRepository, auditLoggingService);
        order.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        order.verify(userRepository).findById(STAFF_ID);
        order.verify(roleRepository).findRole(PROGRAM_ID, STAFF_ID);
        order.verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        order.verify(screeningRepository).saveAndFlush(screening);
        order.verify(auditLoggingService).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAssignmentBeforeIdempotencyWhenRequesterIsNotProgrammer() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        doThrow(new ForbiddenException()).when(authorization).requireProgrammer(PROGRAM_ID);

        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "handler-key"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
        verify(programRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void concealsUnknownOrDeletedScreeningDuringAssignment() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "handler-key"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "ASSIGNMENT", mode = EnumSource.Mode.EXCLUDE)
    void assignmentRequiresAssignmentProgramPhase(ProgramState state) {
        ProgramEntity program = program(state);
        ScreeningEntity screening = submitted(program, null, 0);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "wrong-phase"))
                .isInstanceOf(InvalidStateException.class);
        verify(screeningRepository, never()).saveAndFlush(any());
    }

    @Test
    void assignmentRequiresSubmittedScreening() {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = screening(program, ScreeningState.CREATED, null, 0);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "wrong-state"))
                .isInstanceOf(InvalidStateException.class);
    }

    @Test
    void assignmentRejectsUnknownTargetUser() {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = submitted(program, null, 0);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        when(userRepository.findById(STAFF_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "unknown-user"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidTargetRoles")
    void assignmentRejectsTargetOutsideFrozenStaffSet(ProgramRoleType role) {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = submitted(program, null, 0);
        allowAssignment(program, screening, staff, role);
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "role-conflict"))
                .isInstanceOf(RoleConflictException.class);
    }

    @Test
    void assignmentRejectsTargetWithoutProgramRole() {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = submitted(program, null, 0);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        when(roleRepository.findRole(PROGRAM_ID, STAFF_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "not-staff"))
                .isInstanceOf(RoleConflictException.class);
    }

    @Test
    void assignmentRejectsExistingHandlerAndOptimisticConflict() {
        ProgramEntity program = program(ProgramState.ASSIGNMENT);
        ScreeningEntity screening = submitted(program, staff, 3);
        allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 3, new ScreeningHandlerAssignmentRequest(STAFF_ID), "existing"))
                .isInstanceOf(InvalidStateException.class);

        set(screening, "handler", null);
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 2, new ScreeningHandlerAssignmentRequest(STAFF_ID), "stale"))
                .isInstanceOf(OptimisticConcurrencyConflictException.class);
    }

    @Test
    void assignmentReplaysStoredSuccessWithoutTakingMutationLocks() throws Exception {
        ScreeningHandlerAssignmentResponse response = new ScreeningHandlerAssignmentResponse(
                SCREENING_ID, new com.example.cinema.program.api.UserSummaryResponse(
                        STAFF_ID, "staff", "Staff Person"), ScreeningState.SUBMITTED, 5);
        encodedResponse.set(response);
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(idempotencyManager.execute(eq("SCREENING.HANDLER.ASSIGN"), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(200, "{\"stored\":true}", true));

        ScreeningCommandResult<ScreeningHandlerAssignmentResponse> result = service.assignHandler(
                SCREENING_ID, 4, new ScreeningHandlerAssignmentRequest(STAFF_ID), "replay");
        assertThat(result.replayed()).isTrue();
        assertThat(result.body()).isEqualTo(response);
        verify(programRepository, never()).findByIdForUpdate(any());
        verify(screeningRepository, never()).findActiveByIdForUpdate(any());
    }

    @Test
    void assignmentPayloadMismatchIsRejectedWithoutTakingMutationLocks() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(idempotencyManager.execute(eq("SCREENING.HANDLER.ASSIGN"), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "mismatch"))
                .isInstanceOf(IdempotencyConflictException.class);
        verify(programRepository, never()).findByIdForUpdate(any());
    }

    @Test
    void assignmentPropagatesPessimisticConflictAndDoesNotAudit() {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID))
                .thenThrow(new PessimisticLockingFailureException("busy"));
        assertThatThrownBy(() -> service.assignHandler(
                SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "busy"))
                .isInstanceOf(PessimisticLockingFailureException.class);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void assignedHandlerCreatesReviewAndScreeningBecomesReviewedAtomically() {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 6);
        allowReview(program, screening);
        when(authorization.currentUser()).thenReturn(identity(STAFF_ID, "staff", "Staff Person"));

        ScreeningCommandResult<ScreeningReviewResponse> result = service.submitReview(
                SCREENING_ID, 6, new ScreeningReviewRequest(new BigDecimal("8.50"), "  Strong work.  "),
                "review-key");

        assertThat(result.status()).isEqualTo(201);
        assertThat(result.body().state()).isEqualTo(ScreeningState.REVIEWED);
        assertThat(result.body().numericScore()).isEqualByComparingTo("8.50");
        assertThat(result.body().detailedComments()).isEqualTo("Strong work.");
        assertThat(result.body().reviewer().userId()).isEqualTo(STAFF_ID);
        assertThat(result.body().createdAt()).isEqualTo(NOW);
        assertThat(screening.getState()).isEqualTo(ScreeningState.REVIEWED);

        ArgumentCaptor<ReviewEntity> review = ArgumentCaptor.forClass(ReviewEntity.class);
        InOrder order = inOrder(programRepository, screeningRepository, reviewRepository, auditLoggingService);
        order.verify(programRepository).findByIdForUpdate(PROGRAM_ID);
        order.verify(screeningRepository).findActiveByIdForUpdate(SCREENING_ID);
        order.verify(reviewRepository).existsByScreeningId(SCREENING_ID);
        order.verify(reviewRepository).saveAndFlush(review.capture());
        order.verify(screeningRepository).saveAndFlush(screening);
        order.verify(auditLoggingService).recordUserAction(any(), any(), any(), any(), any(), any(), any());
        assertThat(review.getValue().getScreening()).isSameAs(screening);
        assertThat(review.getValue().getStaff()).isSameAs(staff);
        verify(auditLoggingService).recordUserAction(
                eq(STAFF_ID), eq("SCREENING_REVIEW_SUBMITTED"), eq("SCREENING"), eq(SCREENING_ID),
                any(), any(), eq(null));
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> oldSnapshot = ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("rawtypes")
        ArgumentCaptor<Map> newSnapshot = ArgumentCaptor.forClass(Map.class);
        verify(auditLoggingService).recordUserAction(
                eq(STAFF_ID), eq("SCREENING_REVIEW_SUBMITTED"), eq("SCREENING"), eq(SCREENING_ID),
                oldSnapshot.capture(), newSnapshot.capture(), eq(null));
        assertThat(oldSnapshot.getValue())
                .containsEntry("state", ScreeningState.SUBMITTED)
                .containsEntry("reviewId", null);
        assertThat(newSnapshot.getValue())
                .containsEntry("state", ScreeningState.REVIEWED)
                .containsEntry("numericScore", new BigDecimal("8.50"))
                .containsEntry("detailedComments", "Strong work.");
    }

    @ParameterizedTest
    @EnumSource(value = ProgramState.class, names = "REVIEW", mode = EnumSource.Mode.EXCLUDE)
    void reviewRequiresReviewProgramPhase(ProgramState state) {
        ProgramEntity program = program(state);
        ScreeningEntity screening = submitted(program, staff, 0);
        allowReview(program, screening);
        asStaff();
        assertThatThrownBy(() -> service.submitReview(
                SCREENING_ID, 0, validReview(), "wrong-phase"))
                .isInstanceOf(InvalidStateException.class);
        verify(reviewRepository, never()).saveAndFlush(any());
    }

    @Test
    void reviewRejectsWrongScreeningStateAndMissingHandler() {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity wrongState = screening(program, ScreeningState.REVIEWED, staff, 0);
        allowReview(program, wrongState);
        asStaff();
        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 0, validReview(), "wrong-state"))
                .isInstanceOf(InvalidStateException.class);

        ScreeningEntity noHandler = submitted(program, null, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(noHandler));
        doThrow(new ForbiddenException()).when(authorization).requireHandler(noHandler);
        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 0, validReview(), "no-handler"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void differentStaffProgrammerOrSubmitterCannotReview() {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        doThrow(new ForbiddenException()).when(authorization).requireHandler(screening);

        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 0, validReview(), "wrong-reviewer"))
                .isInstanceOf(ForbiddenException.class);
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @ParameterizedTest
    @MethodSource("invalidReviews")
    void reviewValidatesScoreCommentsPrecisionAndLength(
            BigDecimal score, String comments, String expectedField) {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 0);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        asStaff();

        assertThatThrownBy(() -> service.submitReview(
                SCREENING_ID, 0, new ScreeningReviewRequest(score, comments), "invalid"))
                .isInstanceOfSatisfying(FieldValidationException.class, exception ->
                        assertThat(exception.fieldErrors()).extracting(error -> error.field())
                                .contains(expectedField));
        verify(idempotencyManager, never()).execute(any(), any(), any(), any());
    }

    @Test
    void duplicateAndConcurrentReviewAttemptsReturnSafeConflicts() {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 0);
        allowReview(program, screening);
        asStaff();
        when(reviewRepository.existsByScreeningId(SCREENING_ID)).thenReturn(true);
        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 0, validReview(), "duplicate"))
                .isInstanceOf(ReviewAlreadyExistsException.class);

        when(reviewRepository.existsByScreeningId(SCREENING_ID)).thenReturn(false);
        when(reviewRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("unique review"));
        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 0, validReview(), "concurrent"))
                .isInstanceOf(ReviewAlreadyExistsException.class);
        assertThat(screening.getState()).isEqualTo(ScreeningState.SUBMITTED);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void reviewIdempotencyReplayAndPayloadMismatchAreHandledBeforeLocks() throws Exception {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 1);
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        asStaff();
        ScreeningReviewResponse response = new ScreeningReviewResponse(
                UUID.randomUUID(), SCREENING_ID, ScreeningState.REVIEWED, new BigDecimal("9.00"),
                "Excellent", new com.example.cinema.program.api.UserSummaryResponse(
                        STAFF_ID, "staff", "Staff Person"), NOW, 2);
        encodedResponse.set(response);
        when(idempotencyManager.execute(eq("SCREENING.REVIEW.SUBMIT"), eq("replay"), any(), any()))
                .thenReturn(new IdempotencyResult(201, "{\"stored\":true}", true));
        ScreeningCommandResult<ScreeningReviewResponse> replay = service.submitReview(
                SCREENING_ID, 1, validReview(), "replay");
        assertThat(replay.replayed()).isTrue();
        verify(programRepository, never()).findByIdForUpdate(any());

        when(idempotencyManager.execute(eq("SCREENING.REVIEW.SUBMIT"), eq("mismatch"), any(), any()))
                .thenThrow(new IdempotencyConflictException(
                        "IDEMPOTENCY_KEY_REUSED", "The key was reused.", false));
        assertThatThrownBy(() -> service.submitReview(SCREENING_ID, 1, validReview(), "mismatch"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void auditFailureMarksTheTransactionRollbackOnlyForBothCommands() {
        assertRollbackOnAuditFailure(true);
        assertRollbackOnAuditFailure(false);
    }

    @Test
    void screeningUpdateFailureRollsBackTheAlreadyInsertedReview() {
        ProgramEntity program = program(ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, staff, 0);
        allowReview(program, screening);
        asStaff();
        when(screeningRepository.saveAndFlush(screening))
                .thenThrow(new DataIntegrityViolationException("screening write failed"));

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        ScreeningAssignmentReviewService proxy = (ScreeningAssignmentReviewService) factory.getProxy();

        assertThatThrownBy(() -> proxy.submitReview(
                SCREENING_ID, 0, validReview(), "screening-write-failure"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(reviewRepository).saveAndFlush(any(ReviewEntity.class));
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
        verify(auditLoggingService, never()).recordUserAction(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void serviceMethodsDeclareTransactionalBoundary() throws Exception {
        assertThat(ScreeningAssignmentReviewService.class.getMethod(
                "assignHandler", UUID.class, long.class, ScreeningHandlerAssignmentRequest.class, String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
        assertThat(ScreeningAssignmentReviewService.class.getMethod(
                "submitReview", UUID.class, long.class, ScreeningReviewRequest.class, String.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }

    private void assertRollbackOnAuditFailure(boolean assignment) {
        ProgramEntity program = program(assignment ? ProgramState.ASSIGNMENT : ProgramState.REVIEW);
        ScreeningEntity screening = submitted(program, assignment ? null : staff, 0);
        if (assignment) {
            allowAssignment(program, screening, staff, ProgramRoleType.STAFF);
        } else {
            allowReview(program, screening);
            asStaff();
        }
        doThrow(new IllegalStateException("audit unavailable")).when(auditLoggingService)
                .recordUserAction(any(), any(), any(), any(), any(), any(), any());

        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        ProxyFactory factory = new ProxyFactory(service);
        factory.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        ScreeningAssignmentReviewService proxy = (ScreeningAssignmentReviewService) factory.getProxy();

        if (assignment) {
            assertThatThrownBy(() -> proxy.assignHandler(
                    SCREENING_ID, 0, new ScreeningHandlerAssignmentRequest(STAFF_ID), "rollback-handler"))
                    .isInstanceOf(IllegalStateException.class);
        } else {
            assertThatThrownBy(() -> proxy.submitReview(
                    SCREENING_ID, 0, validReview(), "rollback-review"))
                    .isInstanceOf(IllegalStateException.class);
        }
        verify(transactionManager).rollback(transactionStatus);
        verify(transactionManager, never()).commit(transactionStatus);
    }

    private void allowAssignment(
            ProgramEntity program,
            ScreeningEntity screening,
            UserEntity target,
            ProgramRoleType targetRole) {
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(userRepository.findById(target.getId())).thenReturn(Optional.of(target));
        when(roleRepository.findRole(PROGRAM_ID, target.getId())).thenReturn(Optional.of(
                new ProgramRoleEntity(program, target, targetRole, NOW, programmer)));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
    }

    private void allowReview(ProgramEntity program, ScreeningEntity screening) {
        when(screeningRepository.findActiveById(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(programRepository.findByIdForUpdate(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.findActiveByIdForUpdate(SCREENING_ID)).thenReturn(Optional.of(screening));
        when(reviewRepository.existsByScreeningId(SCREENING_ID)).thenReturn(false);
    }

    private void asStaff() {
        when(authorization.currentUser()).thenReturn(identity(STAFF_ID, "staff", "Staff Person"));
    }

    private ProgramEntity program(ProgramState state) {
        ProgramEntity program = new ProgramEntity(
                PROGRAM_ID, programmer, "Program", "Description",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), NOW);
        set(program, "state", state);
        return program;
    }

    private ScreeningEntity submitted(ProgramEntity program, UserEntity handler, long version) {
        return screening(program, ScreeningState.SUBMITTED, handler, version);
    }

    private ScreeningEntity screening(
            ProgramEntity program, ScreeningState state, UserEntity handler, long version) {
        ScreeningEntity screening = new ScreeningEntity(
                SCREENING_ID, program, submitter, "Film", "Cast", "Drama", 100, "Candidate Hall",
                Instant.parse("2027-04-01T10:00:00Z"), Instant.parse("2027-04-01T12:00:00Z"), NOW);
        set(screening, "state", state);
        set(screening, "handler", handler);
        set(screening, "version", version);
        return screening;
    }

    private static UserEntity user(UUID id, String username, String fullName) {
        return new UserEntity(id, username, "hash-never-exposed", fullName);
    }

    private static AuthenticatedUserIdentity identity(UUID id, String username, String fullName) {
        return new AuthenticatedUserIdentity(id, username, fullName);
    }

    private static ScreeningReviewRequest validReview() {
        return new ScreeningReviewRequest(new BigDecimal("8.50"), "Detailed comments");
    }

    private static Stream<ProgramRoleType> invalidTargetRoles() {
        return Stream.of(ProgramRoleType.PROGRAMMER, ProgramRoleType.SUBMITTER);
    }

    private static Stream<Arguments> invalidReviews() {
        return Stream.of(
                Arguments.of(null, "Comments", "numericScore"),
                Arguments.of(new BigDecimal("-0.01"), "Comments", "numericScore"),
                Arguments.of(new BigDecimal("10.01"), "Comments", "numericScore"),
                Arguments.of(new BigDecimal("8.001"), "Comments", "numericScore"),
                Arguments.of(new BigDecimal("8.00"), null, "detailedComments"),
                Arguments.of(new BigDecimal("8.00"), "   ", "detailedComments"),
                Arguments.of(new BigDecimal("8.00"),
                        "x".repeat(ReviewEntity.MAXIMUM_COMMENT_LENGTH + 1), "detailedComments"));
    }

    private static void set(Object target, String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
