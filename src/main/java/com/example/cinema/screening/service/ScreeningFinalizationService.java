package com.example.cinema.screening.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;
import com.example.cinema.common.error.FieldValidationException;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.common.error.SchedulingConflictException;
import com.example.cinema.idempotency.IdempotencyManager;
import com.example.cinema.idempotency.IdempotencyResult;
import com.example.cinema.idempotency.StoredCommandResponse;
import com.example.cinema.program.api.UserSummaryResponse;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScreeningFinalizationService {

    static final String DECISION_OPERATION = "SCREENING.DECISION";
    static final String FINAL_SUBMISSION_OPERATION = "SCREENING.FINAL_SUBMISSION";
    static final String SCHEDULE_OPERATION = "SCREENING.SCHEDULE";

    private final ScreeningRepository screeningRepository;
    private final ProgramRepository programRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScreeningFinalizationService(
            ScreeningRepository screeningRepository,
            ProgramRepository programRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.screeningRepository = screeningRepository;
        this.programRepository = programRepository;
        this.authorization = authorization;
        this.idempotencyManager = idempotencyManager;
        this.auditLoggingService = auditLoggingService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public ScreeningCommandResult<ScreeningDecisionResponse> decide(
            UUID screeningId,
            long expectedVersion,
            ScreeningDecisionRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        UUID programId = activeProgramId(screeningId);
        authorization.requireProgrammer(programId);
        DecisionDetails details = validateDecision(request);

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("screeningId", screeningId);
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("decision", details.decision());
        canonical.put("conditionalNotes", details.conditionalNotes());
        canonical.put("reason", details.reason());
        IdempotencyResult result = idempotencyManager.execute(
                DECISION_OPERATION,
                idempotencyKey,
                canonical,
                () -> decideLocked(actor, programId, screeningId, expectedVersion, details));
        return result(result, ScreeningDecisionResponse.class);
    }

    @Transactional
    public ScreeningCommandResult<ScreeningDetailResponse> finalSubmit(
            UUID screeningId,
            long expectedVersion,
            ScreeningFinalSubmissionRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        ScreeningEntity visibleScreening = activeScreening(screeningId);
        UUID programId = visibleScreening.getProgram().getId();
        authorization.requireOwner(visibleScreening);
        authorization.requireSubmitter(programId);
        FinalContentPatch patch = validateFinalPatch(request);

        IdempotencyResult result = idempotencyManager.execute(
                FINAL_SUBMISSION_OPERATION,
                idempotencyKey,
                patch.canonical(screeningId, expectedVersion),
                () -> finalSubmitLocked(actor, programId, screeningId, expectedVersion, patch));
        return result(result, ScreeningDetailResponse.class);
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public ScreeningCommandResult<ScreeningScheduleResponse> schedule(
            UUID screeningId,
            long expectedVersion,
            ScreeningScheduleRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        UUID programId = activeProgramId(screeningId);
        authorization.requireProgrammer(programId);
        ScheduleDetails details = validateSchedule(request);

        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("screeningId", screeningId);
        canonical.put("expectedVersion", expectedVersion);
        canonical.put("finalAuditoriumName", details.finalAuditoriumName());
        canonical.put("startTime", details.startTime());
        canonical.put("endTime", details.endTime());
        IdempotencyResult result = idempotencyManager.execute(
                SCHEDULE_OPERATION,
                idempotencyKey,
                canonical,
                () -> scheduleLocked(actor, programId, screeningId, expectedVersion, details));
        return result(result, ScreeningScheduleResponse.class);
    }

    private StoredCommandResponse decideLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            UUID screeningId,
            long expectedVersion,
            DecisionDetails details) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        ScreeningEntity screening = lockedScreening(screeningId, programId);
        checkVersion(screening, expectedVersion);

        Map<String, Object> oldSnapshot = decisionSnapshot(screening);
        if (program.getState() == ProgramState.SCHEDULING) {
            if (screening.getState() != ScreeningState.REVIEWED) {
                throw new InvalidStateException();
            }
            if (details.decision() == ScreeningDecision.APPROVE) {
                screening.approve(details.conditionalNotes());
            } else {
                screening.rejectReviewed(details.reason());
            }
        } else if (program.getState() == ProgramState.DECISION
                && details.decision() == ScreeningDecision.REJECT
                && screening.getState() == ScreeningState.APPROVED
                && screening.getFinalSubmittedAt() != null) {
            screening.rejectFinallySubmitted(details.reason());
        } else {
            throw new InvalidStateException();
        }

        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_DECIDED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                decisionSnapshot(screening),
                details.reason());
        return stored(200, new ScreeningDecisionResponse(
                screeningId,
                details.decision(),
                screening.getState(),
                screening.getConditionalNotes(),
                screening.getRejectionReason(),
                screening.getVersion()));
    }

    private StoredCommandResponse finalSubmitLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            UUID screeningId,
            long expectedVersion,
            FinalContentPatch patch) {
        ProgramEntity program = lockedProgram(programId);
        ScreeningEntity screening = lockedScreening(screeningId, programId);
        authorization.requireOwner(screening);
        authorization.requireSubmitter(programId);
        checkVersion(screening, expectedVersion);
        if (program.getState() != ProgramState.FINAL_PUBLICATION
                || screening.getState() != ScreeningState.APPROVED
                || screening.getFinalSubmittedAt() != null) {
            throw new InvalidStateException();
        }

        FinalContent content = patch.applyTo(screening);
        validateCompleteFinalContent(content);
        Map<String, Object> oldSnapshot = contentSnapshot(screening);
        screening.recordFinalSubmission(
                content.filmTitle(),
                content.cast(),
                content.genre(),
                content.durationMinutes(),
                content.candidateAuditoriumName(),
                content.startTime(),
                content.endTime(),
                clock.instant());
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_FINAL_SUBMITTED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                contentSnapshot(screening),
                null);
        return stored(200, detailResponse(screening));
    }

    private StoredCommandResponse scheduleLocked(
            AuthenticatedUserIdentity actor,
            UUID programId,
            UUID screeningId,
            long expectedVersion,
            ScheduleDetails details) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        ScreeningEntity screening = lockedScreening(screeningId, programId);
        checkVersion(screening, expectedVersion);
        if (program.getState() != ProgramState.DECISION
                || screening.getState() != ScreeningState.APPROVED
                || screening.getFinalSubmittedAt() == null) {
            throw new InvalidStateException();
        }
        validateScheduleInterval(details, screening.getDurationMinutes());

        if (!screeningRepository.findSchedulingConflictsForUpdate(
                screeningId,
                details.finalAuditoriumName(),
                details.startTime(),
                details.endTime()).isEmpty()) {
            throw new SchedulingConflictException();
        }

        Map<String, Object> oldSnapshot = scheduleSnapshot(screening);
        screening.schedule(details.finalAuditoriumName(), details.startTime(), details.endTime());
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_SCHEDULED",
                "SCREENING",
                screeningId,
                oldSnapshot,
                scheduleSnapshot(screening),
                null);
        return stored(200, new ScreeningScheduleResponse(
                screeningId,
                screening.getState(),
                screening.getFinalAuditoriumName(),
                screening.getStartTime(),
                screening.getEndTime(),
                screening.getVersion()));
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

    private static DecisionDetails validateDecision(ScreeningDecisionRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request == null || request.decision() == null) {
            errors.add(new FieldErrorDetail("decision", "is required"));
            throw validation("SCREENING_DECISION_INVALID", "The Screening decision is invalid.", errors);
        }
        String notes = optionalText(request.conditionalNotes(), "conditionalNotes", Integer.MAX_VALUE);
        String reason = optionalText(request.reason(), "reason", Integer.MAX_VALUE);
        if (request.decision() == ScreeningDecision.APPROVE && reason != null) {
            errors.add(new FieldErrorDetail("reason", "is accepted only for REJECT"));
        }
        if (request.decision() == ScreeningDecision.REJECT) {
            if (reason == null) {
                errors.add(new FieldErrorDetail("reason", "must be nonblank for REJECT"));
            }
            if (notes != null) {
                errors.add(new FieldErrorDetail("conditionalNotes", "is accepted only for APPROVE"));
            }
        }
        if (!errors.isEmpty()) {
            throw validation("SCREENING_DECISION_INVALID", "The Screening decision is invalid.", errors);
        }
        return new DecisionDetails(request.decision(), notes, reason);
    }

    private static FinalContentPatch validateFinalPatch(ScreeningFinalSubmissionRequest request) {
        if (request == null) {
            throw new InvalidInputException("VALIDATION_FAILED", "A request body is required.");
        }
        return new FinalContentPatch(
                request.isFilmTitleSupplied(),
                request.isFilmTitleSupplied() ? requiredText(request.getFilmTitle(), "filmTitle", 255) : null,
                request.isCastSupplied(),
                request.isCastSupplied() ? requiredText(request.getCast(), "cast", Integer.MAX_VALUE) : null,
                request.isGenreSupplied(),
                request.isGenreSupplied() ? requiredText(request.getGenre(), "genre", 255) : null,
                request.isDurationMinutesSupplied(),
                request.isDurationMinutesSupplied() ? requiredDuration(request.getDurationMinutes()) : null,
                request.isCandidateAuditoriumNameSupplied(),
                request.isCandidateAuditoriumNameSupplied()
                        ? requiredText(request.getCandidateAuditoriumName(), "candidateAuditoriumName", 255)
                        : null,
                request.isStartTimeSupplied(),
                request.isStartTimeSupplied() ? requiredValue(request.getStartTime(), "startTime") : null,
                request.isEndTimeSupplied(),
                request.isEndTimeSupplied() ? requiredValue(request.getEndTime(), "endTime") : null);
    }

    private static void validateCompleteFinalContent(FinalContent content) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        requiredFinalText(errors, "filmTitle", content.filmTitle());
        requiredFinalText(errors, "cast", content.cast());
        requiredFinalText(errors, "genre", content.genre());
        requiredFinalText(errors, "candidateAuditoriumName", content.candidateAuditoriumName());
        if (content.durationMinutes() == null || content.durationMinutes() <= 0) {
            errors.add(new FieldErrorDetail("durationMinutes", "must be positive"));
        }
        if (content.startTime() == null) {
            errors.add(new FieldErrorDetail("startTime", "is required"));
        }
        if (content.endTime() == null) {
            errors.add(new FieldErrorDetail("endTime", "is required"));
        }
        if (content.startTime() != null && content.endTime() != null) {
            if (!content.endTime().isAfter(content.startTime())) {
                errors.add(new FieldErrorDetail("endTime", "must be after startTime"));
            } else if (content.durationMinutes() != null && content.durationMinutes() > 0
                    && Duration.between(content.startTime(), content.endTime()).compareTo(
                            Duration.ofMinutes(content.durationMinutes())) < 0) {
                errors.add(new FieldErrorDetail("endTime", "must provide an interval of at least durationMinutes"));
            }
        }
        if (!errors.isEmpty()) {
            throw validation(
                    "FINAL_SUBMISSION_INVALID", "The final Screening content is incomplete or invalid.", errors);
        }
    }

    private static ScheduleDetails validateSchedule(ScreeningScheduleRequest request) {
        List<FieldErrorDetail> errors = new ArrayList<>();
        if (request == null) {
            errors.add(new FieldErrorDetail("finalAuditoriumName", "is required"));
            errors.add(new FieldErrorDetail("startTime", "is required"));
            errors.add(new FieldErrorDetail("endTime", "is required"));
            throw validation("FINAL_SCHEDULE_INVALID", "The final schedule is invalid.", errors);
        }
        String auditorium = null;
        if (request.finalAuditoriumName() == null || request.finalAuditoriumName().isBlank()) {
            errors.add(new FieldErrorDetail("finalAuditoriumName", "must not be blank"));
        } else {
            auditorium = request.finalAuditoriumName().strip();
            if (auditorium.length() > 255) {
                errors.add(new FieldErrorDetail("finalAuditoriumName", "must contain at most 255 characters"));
            }
        }
        if (request.startTime() == null) {
            errors.add(new FieldErrorDetail("startTime", "is required"));
        }
        if (request.endTime() == null) {
            errors.add(new FieldErrorDetail("endTime", "is required"));
        }
        if (request.startTime() != null && request.endTime() != null
                && !request.endTime().isAfter(request.startTime())) {
            errors.add(new FieldErrorDetail("endTime", "must be after startTime"));
        }
        if (!errors.isEmpty()) {
            throw validation("FINAL_SCHEDULE_INVALID", "The final schedule is invalid.", errors);
        }
        return new ScheduleDetails(auditorium, request.startTime(), request.endTime());
    }

    private static void validateScheduleInterval(ScheduleDetails details, Integer durationMinutes) {
        if (durationMinutes == null || durationMinutes <= 0) {
            throw validation(
                    "FINAL_SCHEDULE_INVALID",
                    "The final schedule is invalid.",
                    List.of(new FieldErrorDetail("durationMinutes", "must be positive")));
        }
        if (Duration.between(details.startTime(), details.endTime()).compareTo(
                Duration.ofMinutes(durationMinutes)) < 0) {
            throw validation(
                    "FINAL_SCHEDULE_INVALID",
                    "The final schedule is invalid.",
                    List.of(new FieldErrorDetail(
                            "endTime", "must provide an interval of at least durationMinutes")));
        }
    }

    private static FieldValidationException validation(
            String code, String detail, List<FieldErrorDetail> errors) {
        return new FieldValidationException(code, detail, errors);
    }

    private static String optionalText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return requiredText(value, field, maximumLength);
    }

    private static String requiredText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw validation(
                    "VALIDATION_FAILED",
                    "One or more request fields are invalid.",
                    List.of(new FieldErrorDetail(field, "must not be blank when supplied")));
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw validation(
                    "VALIDATION_FAILED",
                    "One or more request fields are invalid.",
                    List.of(new FieldErrorDetail(field, "is too long")));
        }
        return normalized;
    }

    private static Integer requiredDuration(Integer value) {
        if (value == null || value <= 0) {
            throw validation(
                    "VALIDATION_FAILED",
                    "One or more request fields are invalid.",
                    List.of(new FieldErrorDetail("durationMinutes", "must be positive")));
        }
        return value;
    }

    private static <T> T requiredValue(T value, String field) {
        if (value == null) {
            throw validation(
                    "VALIDATION_FAILED",
                    "One or more request fields are invalid.",
                    List.of(new FieldErrorDetail(field, "must not be null when supplied")));
        }
        return value;
    }

    private static void requiredFinalText(List<FieldErrorDetail> errors, String field, String value) {
        if (value == null || value.isBlank()) {
            errors.add(new FieldErrorDetail(field, "must be nonblank"));
        }
    }

    private static Map<String, Object> decisionSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("state", screening.getState());
        snapshot.put("conditionalNotes", screening.getConditionalNotes());
        snapshot.put("rejectionReason", screening.getRejectionReason());
        snapshot.put("finalSubmittedAt", screening.getFinalSubmittedAt());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static Map<String, Object> contentSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("filmTitle", screening.getFilmTitle());
        snapshot.put("cast", screening.getCastText());
        snapshot.put("genre", screening.getGenre());
        snapshot.put("durationMinutes", screening.getDurationMinutes());
        snapshot.put("candidateAuditoriumName", screening.getCandidateAuditoriumName());
        snapshot.put("startTime", screening.getStartTime());
        snapshot.put("endTime", screening.getEndTime());
        snapshot.put("state", screening.getState());
        snapshot.put("finalSubmittedAt", screening.getFinalSubmittedAt());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static Map<String, Object> scheduleSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("state", screening.getState());
        snapshot.put("candidateAuditoriumName", screening.getCandidateAuditoriumName());
        snapshot.put("finalAuditoriumName", screening.getFinalAuditoriumName());
        snapshot.put("startTime", screening.getStartTime());
        snapshot.put("endTime", screening.getEndTime());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private static ScreeningDetailResponse detailResponse(ScreeningEntity screening) {
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

    private record DecisionDetails(
            ScreeningDecision decision, String conditionalNotes, String reason) {
    }

    private record FinalContent(
            String filmTitle,
            String cast,
            String genre,
            Integer durationMinutes,
            String candidateAuditoriumName,
            Instant startTime,
            Instant endTime) {
    }

    private record FinalContentPatch(
            boolean filmTitleSupplied,
            String filmTitle,
            boolean castSupplied,
            String cast,
            boolean genreSupplied,
            String genre,
            boolean durationMinutesSupplied,
            Integer durationMinutes,
            boolean candidateAuditoriumNameSupplied,
            String candidateAuditoriumName,
            boolean startTimeSupplied,
            Instant startTime,
            boolean endTimeSupplied,
            Instant endTime) {

        FinalContent applyTo(ScreeningEntity screening) {
            return new FinalContent(
                    filmTitleSupplied ? filmTitle : screening.getFilmTitle(),
                    castSupplied ? cast : screening.getCastText(),
                    genreSupplied ? genre : screening.getGenre(),
                    durationMinutesSupplied ? durationMinutes : screening.getDurationMinutes(),
                    candidateAuditoriumNameSupplied
                            ? candidateAuditoriumName : screening.getCandidateAuditoriumName(),
                    startTimeSupplied ? startTime : screening.getStartTime(),
                    endTimeSupplied ? endTime : screening.getEndTime());
        }

        Map<String, Object> canonical(UUID screeningId, long expectedVersion) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("screeningId", screeningId);
            content.put("expectedVersion", expectedVersion);
            content.put("filmTitleSupplied", filmTitleSupplied);
            content.put("filmTitle", filmTitle);
            content.put("castSupplied", castSupplied);
            content.put("cast", cast);
            content.put("genreSupplied", genreSupplied);
            content.put("genre", genre);
            content.put("durationMinutesSupplied", durationMinutesSupplied);
            content.put("durationMinutes", durationMinutes);
            content.put("candidateAuditoriumNameSupplied", candidateAuditoriumNameSupplied);
            content.put("candidateAuditoriumName", candidateAuditoriumName);
            content.put("startTimeSupplied", startTimeSupplied);
            content.put("startTime", startTime);
            content.put("endTimeSupplied", endTimeSupplied);
            content.put("endTime", endTime);
            return content;
        }
    }

    private record ScheduleDetails(
            String finalAuditoriumName, Instant startTime, Instant endTime) {
    }
}
