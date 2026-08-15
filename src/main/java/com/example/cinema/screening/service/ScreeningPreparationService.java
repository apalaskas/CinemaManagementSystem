package com.example.cinema.screening.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.InvalidStateException;
import com.example.cinema.common.error.OptimisticConcurrencyConflictException;
import com.example.cinema.common.error.ResourceNotFoundException;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScreeningPreparationService {

    static final String CREATE_OPERATION = "SCREENING.CREATE";
    static final String UPDATE_OPERATION = "SCREENING.UPDATE";

    private final ScreeningRepository screeningRepository;
    private final ProgramRepository programRepository;
    private final ProgramRoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ScreeningPreparationService(
            ScreeningRepository screeningRepository,
            ProgramRepository programRepository,
            ProgramRoleRepository roleRepository,
            UserRepository userRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper,
            Clock clock) {
        this.screeningRepository = screeningRepository;
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
    public ScreeningCommandResult<ScreeningDetailResponse> create(
            UUID programId,
            ScreeningCreateRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        DraftDetails details = validateCreation(request);
        IdempotencyResult result = idempotencyManager.execute(
                CREATE_OPERATION,
                idempotencyKey,
                details.canonical(programId),
                () -> createDraft(actor, programId, details));
        return result(result);
    }

    @Transactional
    public ScreeningCommandResult<ScreeningDetailResponse> update(
            UUID screeningId,
            long expectedVersion,
            ScreeningUpdateRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        DraftPatch patch = validatePatch(request);
        IdempotencyResult result = idempotencyManager.execute(
                UPDATE_OPERATION,
                idempotencyKey,
                patch.canonical(screeningId, expectedVersion),
                () -> updateDraft(actor, screeningId, expectedVersion, patch));
        return result(result);
    }

    @Transactional
    public void withdraw(UUID screeningId, long expectedVersion) {
        UUID programId = activeProgramId(screeningId);
        ProgramEntity program = lockedProgram(programId);
        ScreeningEntity screening = screeningRepository.findActiveByIdForUpdate(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
        authorization.requireOwner(screening);
        authorization.requireSubmitter(programId);
        checkVersion(screening, expectedVersion);
        validateWithdrawalState(screening, program.getState());

        Map<String, ?> oldSnapshot = screeningSnapshot(screening);
        screening.withdraw(clock.instant());
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                authorization.currentUser().userId(),
                "SCREENING_WITHDRAWN",
                "SCREENING",
                screeningId,
                oldSnapshot,
                screeningSnapshot(screening),
                null);
    }

    private StoredCommandResponse createDraft(
            AuthenticatedUserIdentity actor,
            UUID programId,
            DraftDetails details) {
        ProgramEntity program = programRepository.findByIdForUpdate(programId)
                .orElseThrow(ResourceNotFoundException::new);
        ProgramRoleEntity existingRole = roleRepository.findRole(programId, actor.userId()).orElse(null);
        if (existingRole != null && existingRole.getRole() != ProgramRoleType.SUBMITTER) {
            throw new RoleConflictException();
        }
        if (!draftsAllowed(program.getState())) {
            throw new InvalidStateException();
        }

        UserEntity submitter = userRepository.findById(actor.userId())
                .orElseThrow(ResourceNotFoundException::new);
        Instant now = clock.instant();
        boolean submitterRoleCreated = existingRole == null;
        if (submitterRoleCreated) {
            try {
                roleRepository.saveAndFlush(new ProgramRoleEntity(
                        program, submitter, ProgramRoleType.SUBMITTER, now, submitter));
            } catch (DataIntegrityViolationException exception) {
                throw new RoleConflictException();
            }
        }

        ScreeningEntity screening = new ScreeningEntity(
                UUID.randomUUID(),
                program,
                submitter,
                details.filmTitle(),
                details.cast(),
                details.genre(),
                details.durationMinutes(),
                details.candidateAuditoriumName(),
                details.startTime(),
                details.endTime(),
                now);
        screeningRepository.saveAndFlush(screening);
        Map<String, Object> newSnapshot = new LinkedHashMap<>(screeningSnapshot(screening));
        newSnapshot.put("submitterRoleCreated", submitterRoleCreated);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_CREATED",
                "SCREENING",
                screening.getId(),
                Map.of(),
                newSnapshot,
                null);
        return stored(201, response(screening));
    }

    private StoredCommandResponse updateDraft(
            AuthenticatedUserIdentity actor,
            UUID screeningId,
            long expectedVersion,
            DraftPatch patch) {
        UUID programId = activeProgramId(screeningId);
        ProgramEntity program = lockedProgram(programId);
        ScreeningEntity screening = activeScreening(screeningId);
        authorization.requireOwner(screening);
        authorization.requireSubmitter(programId);
        checkVersion(screening, expectedVersion);
        if (screening.getState() != ScreeningState.CREATED
                || !draftsAllowed(program.getState())) {
            throw new InvalidStateException();
        }

        DraftDetails resulting = patch.applyTo(screening);
        validateDetails(resulting);
        Map<String, ?> oldSnapshot = screeningSnapshot(screening);
        screening.updateDraft(
                resulting.filmTitle(), resulting.cast(), resulting.genre(),
                resulting.durationMinutes(), resulting.candidateAuditoriumName(),
                resulting.startTime(), resulting.endTime());
        screeningRepository.saveAndFlush(screening);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "SCREENING_DRAFT_UPDATED",
                "SCREENING",
                screening.getId(),
                oldSnapshot,
                screeningSnapshot(screening),
                null);
        return stored(200, response(screening));
    }

    private ScreeningEntity activeScreening(UUID screeningId) {
        return screeningRepository.findActiveById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private UUID activeProgramId(UUID screeningId) {
        return screeningRepository.findActiveProgramIdById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private ProgramEntity lockedProgram(UUID programId) {
        return programRepository.findByIdForUpdate(programId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private static void checkVersion(ScreeningEntity screening, long expectedVersion) {
        if (screening.getVersion() != expectedVersion) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static boolean draftsAllowed(ProgramState state) {
        return state == ProgramState.CREATED || state == ProgramState.SUBMISSION;
    }

    private static void validateWithdrawalState(ScreeningEntity screening, ProgramState programState) {
        if (screening.getState() == ScreeningState.CREATED && draftsAllowed(programState)) {
            return;
        }
        if (screening.getState() == ScreeningState.SUBMITTED && programState == ProgramState.SUBMISSION) {
            return;
        }
        throw new InvalidStateException();
    }

    private static DraftDetails validateCreation(ScreeningCreateRequest request) {
        if (request == null) {
            throw new InvalidInputException("VALIDATION_FAILED", "A request body is required.");
        }
        DraftDetails details = new DraftDetails(
                optionalText(request.filmTitle(), "filmTitle", 255),
                optionalText(request.cast(), "cast", Integer.MAX_VALUE),
                optionalText(request.genre(), "genre", 255),
                positiveDuration(request.durationMinutes()),
                optionalText(request.candidateAuditoriumName(), "candidateAuditoriumName", 255),
                request.startTime(),
                request.endTime());
        validateDetails(details);
        return details;
    }

    private static DraftPatch validatePatch(ScreeningUpdateRequest request) {
        if (request == null || !request.hasAnySuppliedField()) {
            throw new InvalidInputException(
                    "EMPTY_SCREENING_UPDATE",
                    "At least one editable Screening draft field must be supplied.");
        }
        return new DraftPatch(
                request.isFilmTitleSupplied(),
                request.isFilmTitleSupplied() ? requiredPatchText(request.getFilmTitle(), "filmTitle", 255) : null,
                request.isCastSupplied(),
                request.isCastSupplied() ? requiredPatchText(request.getCast(), "cast", Integer.MAX_VALUE) : null,
                request.isGenreSupplied(),
                request.isGenreSupplied() ? requiredPatchText(request.getGenre(), "genre", 255) : null,
                request.isDurationMinutesSupplied(),
                request.isDurationMinutesSupplied() ? requiredDuration(request.getDurationMinutes()) : null,
                request.isCandidateAuditoriumNameSupplied(),
                request.isCandidateAuditoriumNameSupplied()
                        ? requiredPatchText(request.getCandidateAuditoriumName(), "candidateAuditoriumName", 255)
                        : null,
                request.isStartTimeSupplied(),
                request.isStartTimeSupplied() ? requiredPatchValue(request.getStartTime(), "startTime") : null,
                request.isEndTimeSupplied(),
                request.isEndTimeSupplied() ? requiredPatchValue(request.getEndTime(), "endTime") : null);
    }

    private static void validateDetails(DraftDetails details) {
        Instant start = details.startTime();
        Instant end = details.endTime();
        if (start != null && end != null) {
            if (!end.isAfter(start)) {
                throw new InvalidInputException(
                        "INVALID_SCREENING_INTERVAL", "endTime must be after startTime.");
            }
            if (details.durationMinutes() != null
                    && Duration.between(start, end).compareTo(
                            Duration.ofMinutes(details.durationMinutes())) < 0) {
                throw new InvalidInputException(
                        "INVALID_SCREENING_INTERVAL",
                        "The Screening interval must be at least durationMinutes.");
            }
        }
    }

    private static String optionalText(String value, String field, int maximumLength) {
        if (value == null) {
            return null;
        }
        return requiredPatchText(value, field, maximumLength);
    }

    private static String requiredPatchText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException(
                    "VALIDATION_FAILED", field + " must not be blank when supplied.");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new InvalidInputException("VALIDATION_FAILED", field + " is too long.");
        }
        return normalized;
    }

    private static Integer positiveDuration(Integer value) {
        if (value == null) {
            return null;
        }
        return requiredDuration(value);
    }

    private static Integer requiredDuration(Integer value) {
        if (value == null || value <= 0) {
            throw new InvalidInputException(
                    "INVALID_SCREENING_DURATION", "durationMinutes must be positive when supplied.");
        }
        return value;
    }

    private static <T> T requiredPatchValue(T value, String field) {
        if (value == null) {
            throw new InvalidInputException(
                    "VALIDATION_FAILED", field + " must not be null when supplied.");
        }
        return value;
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

    private static Map<String, Object> screeningSnapshot(ScreeningEntity screening) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("screeningId", screening.getId());
        snapshot.put("programId", screening.getProgram().getId());
        snapshot.put("submitterUserId", screening.getSubmitter().getId());
        snapshot.put("filmTitle", screening.getFilmTitle());
        snapshot.put("cast", screening.getCastText());
        snapshot.put("genre", screening.getGenre());
        snapshot.put("durationMinutes", screening.getDurationMinutes());
        snapshot.put("candidateAuditoriumName", screening.getCandidateAuditoriumName());
        snapshot.put("startTime", screening.getStartTime());
        snapshot.put("endTime", screening.getEndTime());
        snapshot.put("state", screening.getState());
        snapshot.put("createdAt", screening.getCreatedAt());
        snapshot.put("deletedAt", screening.getDeletedAt());
        snapshot.put("version", screening.getVersion());
        return snapshot;
    }

    private StoredCommandResponse stored(int status, ScreeningDetailResponse response) {
        try {
            return new StoredCommandResponse(status, objectMapper.writeValueAsString(response));
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

    private record DraftDetails(
            String filmTitle,
            String cast,
            String genre,
            Integer durationMinutes,
            String candidateAuditoriumName,
            Instant startTime,
            Instant endTime) {

        Map<String, Object> canonical(UUID programId) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("programId", programId);
            content.put("filmTitle", filmTitle);
            content.put("cast", cast);
            content.put("genre", genre);
            content.put("durationMinutes", durationMinutes);
            content.put("candidateAuditoriumName", candidateAuditoriumName);
            content.put("startTime", startTime);
            content.put("endTime", endTime);
            return content;
        }
    }

    private record DraftPatch(
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

        DraftDetails applyTo(ScreeningEntity screening) {
            return new DraftDetails(
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
}
