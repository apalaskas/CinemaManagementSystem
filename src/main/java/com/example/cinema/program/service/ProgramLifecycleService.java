package com.example.cinema.program.service;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProgramLifecycleService {

    static final String TRANSITION_OPERATION = "PROGRAM.TRANSITION";
    static final String FINAL_SUBMISSION_MISSING = "FINAL_SUBMISSION_MISSING";

    private final ProgramRepository programRepository;
    private final ProgramRoleRepository roleRepository;
    private final ScreeningRepository screeningRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProgramLifecycleService(
            ProgramRepository programRepository,
            ProgramRoleRepository roleRepository,
            ScreeningRepository screeningRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.programRepository = programRepository;
        this.roleRepository = roleRepository;
        this.screeningRepository = screeningRepository;
        this.authorization = authorization;
        this.idempotencyManager = idempotencyManager;
        this.auditLoggingService = auditLoggingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ProgramCommandResult<ProgramTransitionResponse> transition(
            UUID programId,
            long expectedVersion,
            ProgramTransitionRequest request,
            String idempotencyKey) {
        authorization.requireProgrammer(programId);
        ProgramState targetState = requireTargetState(request);
        AuthenticatedUserIdentity actor = authorization.currentUser();
        IdempotencyResult result = idempotencyManager.execute(
                TRANSITION_OPERATION,
                idempotencyKey,
                Map.of(
                        "programId", programId,
                        "expectedVersion", expectedVersion,
                        "targetState", targetState),
                () -> transitionLocked(actor, programId, expectedVersion, targetState));
        return result(result, ProgramTransitionResponse.class);
    }

    private StoredCommandResponse transitionLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            long expectedVersion,
            ProgramState targetState) {
        ProgramEntity program = programRepository.findByIdForUpdate(programId)
                .orElseThrow(ResourceNotFoundException::new);
        authorization.requireProgrammer(programId);
        checkVersion(program, expectedVersion);

        ProgramState oldState = program.getState();
        if (!oldState.canTransitionTo(targetState)) {
            throw new InvalidStateException();
        }

        int automaticallyRejected = validateAndApplySideEffects(programId, targetState);
        Instant transitionedAt = clock.instant();
        program.transitionTo(targetState);
        programRepository.saveAndFlush(program);

        auditLoggingService.recordUserAction(
                actor.userId(),
                "PROGRAM_STATE_TRANSITIONED",
                "PROGRAM",
                programId,
                transitionSnapshot(oldState, expectedVersion, null),
                transitionSnapshot(targetState, program.getVersion(), transitionedAt),
                null);

        ProgramTransitionResponse response = new ProgramTransitionResponse(
                programId,
                oldState,
                targetState,
                program.getVersion(),
                transitionedAt,
                automaticallyRejected);
        return stored(200, response);
    }

    private int validateAndApplySideEffects(UUID programId, ProgramState targetState) {
        return switch (targetState) {
            case SUBMISSION -> {
                require(roleRepository.existsByProgramIdAndRole(programId, ProgramRoleType.STAFF),
                        "At least one STAFF member is required before submissions can open.");
                yield 0;
            }
            case ASSIGNMENT -> 0;
            case REVIEW -> {
                require(screeningRepository.countActiveSubmittedWithoutFrozenStaffHandler(programId) == 0,
                        "Every active submitted Screening must have one handler from the frozen STAFF set.");
                yield 0;
            }
            case SCHEDULING -> {
                require(screeningRepository.countActiveReviewCompletionViolations(programId) == 0,
                        "Every active Screening that entered review must have a completed Review and be REVIEWED.");
                yield 0;
            }
            case FINAL_PUBLICATION -> {
                require(screeningRepository.countActiveDecisionPreparationViolations(programId) == 0,
                        "Every reviewed Screening must be APPROVED or REJECTED before final publication.");
                yield 0;
            }
            case DECISION -> automaticallyRejectMissingFinalSubmissions(programId);
            case ANNOUNCED -> {
                require(screeningRepository.countActiveNonFinalDecisionWorkflow(programId) == 0,
                        "Every active Screening in the decision workflow must be SCHEDULED or REJECTED.");
                yield 0;
            }
            case CREATED -> throw new InvalidStateException();
        };
    }

    private int automaticallyRejectMissingFinalSubmissions(UUID programId) {
        List<ScreeningEntity> screenings =
                screeningRepository.findApprovedWithoutFinalSubmissionForUpdate(programId);
        if (screenings.isEmpty()) {
            return 0;
        }

        Map<UUID, Long> oldVersions = new LinkedHashMap<>();
        screenings.forEach(screening -> {
            oldVersions.put(screening.getId(), screening.getVersion());
            screening.rejectForMissingFinalSubmission(FINAL_SUBMISSION_MISSING);
        });
        screeningRepository.saveAllAndFlush(screenings);

        screenings.forEach(screening -> auditLoggingService.recordSystemAction(
                "SCREENING_AUTOMATICALLY_REJECTED",
                "SCREENING",
                screening.getId(),
                Map.of(
                        "state", ScreeningState.APPROVED,
                        "version", oldVersions.get(screening.getId()),
                        "finalSubmitted", false),
                Map.of(
                        "state", ScreeningState.REJECTED,
                        "version", screening.getVersion(),
                        "rejectionReason", FINAL_SUBMISSION_MISSING),
                FINAL_SUBMISSION_MISSING));
        return screenings.size();
    }

    private static void require(boolean condition, String detail) {
        if (!condition) {
            throw new ProgramTransitionPrerequisiteException(detail);
        }
    }

    private static ProgramState requireTargetState(ProgramTransitionRequest request) {
        if (request == null || request.targetState() == null) {
            throw new InvalidInputException("VALIDATION_FAILED", "targetState must be provided.");
        }
        return request.targetState();
    }

    private static void checkVersion(ProgramEntity program, long expectedVersion) {
        if (program.getVersion() != expectedVersion) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static Map<String, Object> transitionSnapshot(
            ProgramState state,
            long version,
            Instant transitionedAt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("state", state);
        snapshot.put("version", version);
        snapshot.put("transitionedAt", transitionedAt);
        return snapshot;
    }

    private StoredCommandResponse stored(int status, Object response) {
        try {
            return new StoredCommandResponse(status, objectMapper.writeValueAsString(response));
        } catch (JacksonException exception) {
            throw new IllegalStateException("A command response could not be encoded.", exception);
        }
    }

    private <T> ProgramCommandResult<T> result(IdempotencyResult result, Class<T> responseType) {
        try {
            return new ProgramCommandResult<>(
                    result.status(), objectMapper.readValue(result.body(), responseType), result.replayed());
        } catch (JacksonException exception) {
            throw new IllegalStateException("A stored command response could not be decoded.", exception);
        }
    }
}
