package com.example.cinema.screening.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.api.UserSummaryResponse;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScreeningSubmissionService {

    static final String SUBMIT_OPERATION = "SCREENING.SUBMIT";

    private final ScreeningRepository screeningRepository;
    private final ProgramRepository programRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;

    public ScreeningSubmissionService(
            ScreeningRepository screeningRepository,
            ProgramRepository programRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper) {
        this.screeningRepository = screeningRepository;
        this.programRepository = programRepository;
        this.authorization = authorization;
        this.idempotencyManager = idempotencyManager;
        this.auditLoggingService = auditLoggingService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScreeningCommandResult<ScreeningDetailResponse> submit(
            UUID screeningId,
            long expectedVersion,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        ScreeningEntity visibleScreening = activeScreening(screeningId);
        authorizeOwnerSubmitter(visibleScreening);

        Map<String, Object> canonicalContent = new LinkedHashMap<>();
        canonicalContent.put("screeningId", screeningId);
        canonicalContent.put("expectedVersion", expectedVersion);
        IdempotencyResult result = idempotencyManager.execute(
                SUBMIT_OPERATION,
                idempotencyKey,
                canonicalContent,
                () -> submitLocked(actor, screeningId, expectedVersion));
        return result(result);
    }

    private StoredCommandResponse submitLocked(
            AuthenticatedUserIdentity actor,
            UUID screeningId,
            long expectedVersion) {
        ScreeningEntity screening = screeningRepository.findActiveByIdForUpdate(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
        ProgramEntity program = programRepository.findById(screening.getProgram().getId())
                .orElseThrow(ResourceNotFoundException::new);

        authorizeOwnerSubmitter(screening);
        checkVersion(screening, expectedVersion);
        if (screening.getState() != ScreeningState.CREATED || program.getState() != ProgramState.SUBMISSION) {
            throw new InvalidStateException();
        }
        validateComplete(screening);

        Map<String, Object> oldSnapshot = submissionSnapshot(screening);
        screening.submit();
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_SUBMITTED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                submissionSnapshot(screening),
                null);
        return stored(response(screening));
    }

    private ScreeningEntity activeScreening(UUID screeningId) {
        return screeningRepository.findActiveById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private void authorizeOwnerSubmitter(ScreeningEntity screening) {
        authorization.requireOwner(screening);
        authorization.requireSubmitter(screening.getProgram().getId());
    }

    private static void checkVersion(ScreeningEntity screening, long expectedVersion) {
        if (screening.getVersion() != expectedVersion) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static void validateComplete(ScreeningEntity screening) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        requiredText(errors, "filmTitle", screening.getFilmTitle());
        requiredText(errors, "cast", screening.getCastText());
        requiredText(errors, "genre", screening.getGenre());
        Integer duration = screening.getDurationMinutes();
        if (duration == null) {
            errors.add(new FieldErrorDetail("durationMinutes", "is required for submission"));
        } else if (duration <= 0) {
            errors.add(new FieldErrorDetail("durationMinutes", "must be positive"));
        }
        requiredText(errors, "candidateAuditoriumName", screening.getCandidateAuditoriumName());

        Instant start = screening.getStartTime();
        Instant end = screening.getEndTime();
        if (start == null) {
            errors.add(new FieldErrorDetail("startTime", "is required for submission"));
        }
        if (end == null) {
            errors.add(new FieldErrorDetail("endTime", "is required for submission"));
        }
        if (start != null && end != null) {
            if (!end.isAfter(start)) {
                errors.add(new FieldErrorDetail("endTime", "must be after startTime"));
            } else if (duration != null && duration > 0
                    && Duration.between(start, end).compareTo(Duration.ofMinutes(duration)) < 0) {
                errors.add(new FieldErrorDetail(
                        "endTime", "must provide an interval of at least durationMinutes"));
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(
                    "SCREENING_SUBMISSION_INVALID",
                    "The Screening is incomplete or invalid for submission.",
                    errors);
        }
    }

    private static void requiredText(List<FieldErrorDetail> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldErrorDetail(field, "must be nonblank for submission"));
        }
    }

    private static Map<String, Object> submissionSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("state", screening.getState());
        snapshot.put("filmTitle", screening.getFilmTitle());
        snapshot.put("cast", screening.getCastText());
        snapshot.put("genre", screening.getGenre());
        snapshot.put("durationMinutes", screening.getDurationMinutes());
        snapshot.put("candidateAuditoriumName", screening.getCandidateAuditoriumName());
        snapshot.put("startTime", screening.getStartTime());
        snapshot.put("endTime", screening.getEndTime());
        snapshot.put("finalSubmittedAt", screening.getFinalSubmittedAt());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static ScreeningDetailResponse response(ScreeningEntity screening) {
        UserEntity submitter = screening.getSubmitter();
        UserEntity handler = screening.getHandler();
        return new ScreeningDetailResponse(
                screening.getId(),
                screening.getProgram().getId(),
                screening.getFilmTitle(),
                screening.getCastText(),
                screening.getGenre(),
                screening.getDurationMinutes(),
                screening.getCandidateAuditoriumName(),
                screening.getFinalAuditoriumName(),
                screening.getStartTime(),
                screening.getEndTime(),
                screening.getState(),
                screening.getConditionalNotes(),
                screening.getFinalSubmittedAt(),
                screening.getRejectionReason(),
                userSummary(submitter),
                handler == null ? null : userSummary(handler),
                screening.getCreatedAt(),
                screening.getVersion());
    }

    private static UserSummaryResponse userSummary(UserEntity user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getFullName());
    }

    private StoredCommandResponse stored(ScreeningDetailResponse response) {
        try {
            return new StoredCommandResponse(200, objectMapper.writeValueAsString(response));
        } catch (JacksonException exception) {
            throw new IllegalStateException("A command response could not be encoded.", exception);
        }
    }

    private ScreeningCommandResult<ScreeningDetailResponse> result(IdempotencyResult result) {
        try {
            return new ScreeningCommandResult<>(
                    result.status(),
                    objectMapper.readValue(result.body(), ScreeningDetailResponse.class),
                    result.replayed());
        } catch (JacksonException exception) {
            throw new IllegalStateException("A stored command response could not be decoded.", exception);
        }
    }
}
