package com.example.cinema.screening.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.ReviewAlreadyExistsException;
import com.example.cinema.common.error.RoleConflictException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.api.UserSummaryResponse;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScreeningAssignmentReviewService {

    static final String ASSIGN_HANDLER_OPERATION = "SCREENING.HANDLER.ASSIGN";
    static final String SUBMIT_REVIEW_OPERATION = "SCREENING.REVIEW.SUBMIT";

    private final ScreeningRepository screeningRepository;
    private final ReviewRepository reviewRepository;
    private final ProgramRepository programRepository;
    private final ProgramRoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScreeningAssignmentReviewService(
            ScreeningRepository screeningRepository,
            ReviewRepository reviewRepository,
            ProgramRepository programRepository,
            ProgramRoleRepository roleRepository,
            UserRepository userRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.screeningRepository = screeningRepository;
        this.reviewRepository = reviewRepository;
        this.programRepository = programRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.authorization = authorization;
        this.idempotencyManager = idempotencyManager;
        this.auditLoggingService = auditLoggingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ScreeningCommandResult<ScreeningHandlerAssignmentResponse> assignHandler(
            UUID screeningId,
            long expectedVersion,
            ScreeningHandlerAssignmentRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        UUID programId = activeProgramId(screeningId);
        authorization.requireProgrammer(programId);
        if (request == null || request.staffUserId() == null) {
            throw new FieldValidationException(
                    "HANDLER_ASSIGNMENT_INVALID",
                    "The handler assignment is invalid.",
                    List.of(new FieldErrorDetail("staffUserId", "is required")));
        }
        UUID staffUserId = request.staffUserId();

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("screeningId", screeningId);
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("staffUserId", staffUserId);
        IdempotencyResult result = idempotencyManager.execute(
                ASSIGN_HANDLER_OPERATION,
                idempotencyKey,
                canonical,
                () -> assignHandlerLocked(actor, programId, screeningId, expectedVersion, staffUserId));
        return result(result, ScreeningHandlerAssignmentResponse.class);
    }

    @Transactional
    public ScreeningCommandResult<ScreeningReviewResponse> submitReview(
            UUID screeningId,
            long expectedVersion,
            ScreeningReviewRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        ScreeningEntity visibleScreening = activeScreening(screeningId);
        UUID programId = visibleScreening.getProgram().getId();
        authorization.requireHandler(visibleScreening);
        authorization.requireStaff(programId);
        ReviewDetails details = validateReview(request);

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("screeningId", screeningId);
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("numericScore", details.numericScore());
        canonical.put("detailedComments", details.detailedComments());
        IdempotencyResult result = idempotencyManager.execute(
                SUBMIT_REVIEW_OPERATION,
                idempotencyKey,
                canonical,
                () -> submitReviewLocked(actor, programId, screeningId, expectedVersion, details));
        return result(result, ScreeningReviewResponse.class);
    }

    private StoredCommandResponse assignHandlerLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            UUID screeningId,
            long expectedVersion,
            UUID staffUserId) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        if (program.getState() != ProgramState.ASSIGNMENT) {
            throw new InvalidStateException();
        }

        UserEntity staff = userRepository.findById(staffUserId)
                .orElseThrow(ResourceNotFoundException::new);
        ProgramRoleEntity targetRole = roleRepository.findRole(programId, staffUserId)
                .orElseThrow(RoleConflictException::new);
        if (targetRole.getRole() != ProgramRoleType.STAFF) {
            throw new RoleConflictException();
        }

        ScreeningEntity screening = lockedScreening(screeningId, programId);
        checkVersion(screening, expectedVersion);
        if (screening.getState() != ScreeningState.SUBMITTED || screening.getHandler() != null) {
            throw new InvalidStateException();
        }

        Map<String, Object> oldSnapshot = handlerSnapshot(screening);
        screening.assignHandler(staff);
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_HANDLER_ASSIGNED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                handlerSnapshot(screening),
                null);
        return stored(200, handlerResponse(screening));
    }

    private StoredCommandResponse submitReviewLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            UUID screeningId,
            long expectedVersion,
            ReviewDetails details) {
        ProgramEntity program = lockedProgram(programId);
        ScreeningEntity screening = lockedScreening(screeningId, programId);
        authorization.requireHandler(screening);
        authorization.requireStaff(programId);
        if (program.getState() != ProgramState.REVIEW) {
            throw new InvalidStateException();
        }
        checkVersion(screening, expectedVersion);
        if (reviewRepository.existsByScreeningId(screeningId)) {
            throw new ReviewAlreadyExistsException();
        }
        if (screening.getState() != ScreeningState.SUBMITTED || screening.getHandler() == null) {
            throw new InvalidStateException();
        }

        Instant now = clock.instant();
        ReviewEntity review = new ReviewEntity(
                UUID.randomUUID(),
                screening,
                screening.getHandler(),
                details.numericScore(),
                details.detailedComments(),
                now);
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException exception) {
            throw new ReviewAlreadyExistsException();
        }

        Map<String, Object> oldSnapshot = reviewSnapshot(screening, null);
        screening.markReviewed();
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_REVIEW_SUBMITTED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                reviewSnapshot(screening, review),
                null);
        return stored(201, reviewResponse(review, screening));
    }

    private UUID activeProgramId(UUID screeningId) {
        return screeningRepository.findActiveProgramIdById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ScreeningEntity activeScreening(UUID screeningId) {
        return screeningRepository.findActiveById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ProgramEntity lockedProgram(UUID programId) {
        return programRepository.findByIdForUpdate(programId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ScreeningEntity lockedScreening(UUID screeningId, UUID programId) {
        ScreeningEntity screening = screeningRepository.findActiveByIdForUpdate(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
        if (!screening.getProgram().getId().equals(programId)) {
            throw new ResourceNotFoundException();
        }
        return screening;
    }

    private static void checkVersion(ScreeningEntity screening, long expectedVersion) {
        if (screening.getVersion() != expectedVersion) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static ReviewDetails validateReview(ScreeningReviewRequest request) {
        if (request == null) {
            throw reviewValidation(List.of(
                    new FieldErrorDetail("numericScore", "is required"),
                    new FieldErrorDetail("detailedComments", "is required")));
        }
        List<FieldErrorDetail> errors = new java.util.ArrayList<>();
        BigDecimal score = request.numericScore();
        if (score == null) {
            errors.add(new FieldErrorDetail("numericScore", "is required"));
        } else {
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(new BigDecimal("10.00")) > 0) {
                errors.add(new FieldErrorDetail("numericScore", "must be between 0.00 and 10.00 inclusive"));
            }
            if (score.scale() > 2) {
                errors.add(new FieldErrorDetail("numericScore", "must have at most two decimal places"));
            }
        }
        String comments = request.detailedComments();
        String normalizedComments = comments == null ? null : comments.strip();
        if (normalizedComments == null || normalizedComments.isBlank()) {
            errors.add(new FieldErrorDetail("detailedComments", "must not be blank"));
        } else if (normalizedComments.length() > ReviewEntity.MAXIMUM_COMMENT_LENGTH) {
            errors.add(new FieldErrorDetail(
                    "detailedComments",
                    "must not exceed " + ReviewEntity.MAXIMUM_COMMENT_LENGTH + " characters"));
        }
        if (!errors.isEmpty()) {
            throw reviewValidation(errors);
        }
        return new ReviewDetails(score, normalizedComments);
    }

    private static FieldValidationException reviewValidation(List<FieldErrorDetail> errors) {
        return new FieldValidationException(
                "REVIEW_INVALID", "The Review is incomplete or invalid.", errors);
    }

    private static Map<String, Object> handlerSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("state", screening.getState());
        snapshot.put("handlerUserId", screening.getHandler() == null ? null : screening.getHandler().getId());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static Map<String, Object> reviewSnapshot(ScreeningEntity screening, ReviewEntity review) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("state", screening.getState());
        snapshot.put("handlerUserId", screening.getHandler().getId());
        snapshot.put("reviewId", review == null ? null : review.getId());
        snapshot.put("numericScore", review == null ? null : review.getNumericScore());
        snapshot.put("detailedComments", review == null ? null : review.getDetailedComments());
        snapshot.put("reviewCreatedAt", review == null ? null : review.getCreatedAt());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static ScreeningHandlerAssignmentResponse handlerResponse(ScreeningEntity screening) {
        return new ScreeningHandlerAssignmentResponse(
                screening.getId(),
                userSummary(screening.getHandler()),
                screening.getState(),
                screening.getVersion());
    }

    private static ScreeningReviewResponse reviewResponse(ReviewEntity review, ScreeningEntity screening) {
        return new ScreeningReviewResponse(
                review.getId(),
                screening.getId(),
                screening.getState(),
                review.getNumericScore(),
                review.getDetailedComments(),
                userSummary(review.getStaff()),
                review.getCreatedAt(),
                screening.getVersion());
    }

    private static UserSummaryResponse userSummary(UserEntity user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getFullName());
    }

    private StoredCommandResponse stored(int status, Object response) {
        try {
            return new StoredCommandResponse(status, objectMapper.writeValueAsString(response));
        } catch (JacksonException exception) {
            throw new IllegalStateException("A command response could not be encoded.", exception);
        }
    }

    private <T> ScreeningCommandResult<T> result(IdempotencyResult result, Class<T> responseType) {
        try {
            return new ScreeningCommandResult<>(
                    result.status(),
                    objectMapper.readValue(result.body(), responseType),
                    result.replayed());
        } catch (JacksonException exception) {
            throw new IllegalStateException("A stored command response could not be decoded.", exception);
        }
    }

    private record ReviewDetails(BigDecimal numericScore, String detailedComments) {
    }
}
