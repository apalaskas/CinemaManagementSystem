package com.example.cinema.program.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.service.AuditLoggingService;
import com.example.cinema.common.error.CreatorRoleRequiredException;
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

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProgramManagementService {

    static final String CREATE_OPERATION = "PROGRAM.CREATE";
    static final String UPDATE_OPERATION = "PROGRAM.UPDATE";
    static final String ADD_ROLE_OPERATION = "PROGRAM.ROLE.ADD";

    private final ProgramRepository programRepository;
    private final ProgramRoleRepository roleRepository;
    private final UserRepository userRepository;
    private final ContextAwareAuthorizationService authorization;
    private final IdempotencyManager idempotencyManager;
    private final AuditLoggingService auditLoggingService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProgramManagementService(
            ProgramRepository programRepository,
            ProgramRoleRepository roleRepository,
            UserRepository userRepository,
            ContextAwareAuthorizationService authorization,
            IdempotencyManager idempotencyManager,
            AuditLoggingService auditLoggingService,
            ObjectMapper objectMapper,
            Clock clock) {
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
    public ProgramCommandResult<ProgramDetailResponse> create(
            ProgramCreateRequest request,
            String idempotencyKey) {
        AuthenticatedUserIdentity actor = authorization.currentUser();
        ProgramDetails details = validateCreation(request);
        IdempotencyResult result = idempotencyManager.execute(
                CREATE_OPERATION,
                idempotencyKey,
                details,
                () -> createNewProgram(actor, details));
        return result(result, ProgramDetailResponse.class);
    }

    @Transactional
    public ProgramCommandResult<ProgramDetailResponse> update(
            UUID programId,
            long expectedVersion,
            ProgramUpdateRequest request,
            String idempotencyKey) {
        authorization.requireProgrammer(programId);
        AuthenticatedUserIdentity actor = authorization.currentUser();
        ProgramPatch patch = validatePatch(request);
        IdempotencyResult result = idempotencyManager.execute(
                UPDATE_OPERATION,
                idempotencyKey,
                patch.canonical(programId, expectedVersion),
                () -> updateProgram(actor, programId, expectedVersion, patch));
        return result(result, ProgramDetailResponse.class);
    }

    @Transactional
    public ProgramCommandResult<ProgramRoleResponse> addRole(
            UUID programId,
            long expectedVersion,
            ProgramRoleRequest request,
            String idempotencyKey) {
        authorization.requireProgrammer(programId);
        AuthenticatedUserIdentity actor = authorization.currentUser();
        validateManagedRole(request.role());
        IdempotencyResult result = idempotencyManager.execute(
                ADD_ROLE_OPERATION,
                idempotencyKey,
                Map.of(
                        "programId", programId,
                        "expectedVersion", expectedVersion,
                        "userId", request.userId(),
                        "role", request.role()),
                () -> addProgramRole(actor, programId, expectedVersion, request));
        return result(result, ProgramRoleResponse.class);
    }

    @Transactional
    public long removeRole(UUID programId, UUID userId, long expectedVersion) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        checkVersion(program, expectedVersion);

        ProgramRoleEntity assignment = roleRepository.findRole(programId, userId)
                .orElseThrow(ProgramRoleNotFoundException::new);
        if (assignment.getRole() == ProgramRoleType.SUBMITTER) {
            throw new ProgramRoleNotFoundException();
        }
        if (assignment.getRole() == ProgramRoleType.PROGRAMMER
                && program.getCreator().getId().equals(userId)) {
            throw new CreatorRoleRequiredException();
        }
        validateRoleMutationState(program.getState(), assignment.getRole());

        roleRepository.delete(assignment);
        auditLoggingService.recordUserAction(
                authorization.currentUser().userId(),
                "PROGRAM_ROLE_REMOVED",
                "PROGRAM_ROLE",
                programId,
                roleSnapshot(assignment),
                Map.of(),
                null);
        incrementVersion(programId, expectedVersion);
        return expectedVersion + 1;
    }

    @Transactional
    public void delete(UUID programId, long expectedVersion) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        checkVersion(program, expectedVersion);
        if (program.getState() != ProgramState.CREATED) {
            throw new InvalidStateException();
        }

        Map<String, ?> snapshot = programSnapshot(program);
        long roleCount = roleRepository.countByIdProgramId(programId);
        Map<String, Object> deletionSnapshot = new LinkedHashMap<>(snapshot);
        deletionSnapshot.put("roleCount", roleCount);
        auditLoggingService.recordUserAction(
                authorization.currentUser().userId(),
                "PROGRAM_DELETED",
                "PROGRAM",
                programId,
                deletionSnapshot,
                Map.of("programId", programId, "deleted", true),
                null);
        programRepository.delete(program);
        programRepository.flush();
    }

    private StoredCommandResponse createNewProgram(
            AuthenticatedUserIdentity actor,
            ProgramDetails details) {
        UserEntity creator = userRepository.findById(actor.userId())
                .orElseThrow(ResourceNotFoundException::new);
        if (programRepository.existsByNameIgnoreCase(details.name())) {
            throw new ProgramNameExistsException();
        }

        Instant now = clock.instant();
        ProgramEntity program = new ProgramEntity(
                UUID.randomUUID(), creator, details.name(), details.description(),
                details.startDate(), details.endDate(), now);
        try {
            programRepository.saveAndFlush(program);
        } catch (DataIntegrityViolationException exception) {
            throw new ProgramNameExistsException();
        }

        ProgramRoleEntity creatorRole = new ProgramRoleEntity(
                program, creator, ProgramRoleType.PROGRAMMER, now, creator);
        roleRepository.save(creatorRole);
        ProgramDetailResponse response = response(program);
        auditLoggingService.recordUserAction(
                actor.userId(),
                "PROGRAM_CREATED",
                "PROGRAM",
                program.getId(),
                Map.of(),
                programSnapshot(program),
                null);
        return stored(201, response);
    }

    private StoredCommandResponse updateProgram(
            AuthenticatedUserIdentity actor,
            UUID programId,
            long expectedVersion,
            ProgramPatch patch) {
        ProgramEntity program = programRepository.findById(programId)
                .orElseThrow(ResourceNotFoundException::new);
        authorization.requireProgrammer(programId);
        checkVersion(program, expectedVersion);
        if (program.getState() == ProgramState.ANNOUNCED) {
            throw new InvalidStateException();
        }

        ProgramDetails resulting = patch.applyTo(program);
        validateDetails(resulting);
        if (!resulting.name().equals(program.getName())
                && programRepository.existsByNameIgnoreCaseAndIdNot(resulting.name(), programId)) {
            throw new ProgramNameExistsException();
        }

        Map<String, ?> oldSnapshot = programSnapshot(program);
        program.updateDetails(
                resulting.name(), resulting.description(), resulting.startDate(), resulting.endDate());
        try {
            programRepository.saveAndFlush(program);
        } catch (DataIntegrityViolationException exception) {
            throw new ProgramNameExistsException();
        }
        auditLoggingService.recordUserAction(
                actor.userId(),
                "PROGRAM_DETAILS_UPDATED",
                "PROGRAM",
                programId,
                oldSnapshot,
                programSnapshot(program),
                null);
        return stored(200, response(program));
    }

    private StoredCommandResponse addProgramRole(
            AuthenticatedUserIdentity actor,
            UUID programId,
            long expectedVersion,
            ProgramRoleRequest request) {
        ProgramEntity program = lockedProgram(programId);
        authorization.requireProgrammer(programId);
        checkVersion(program, expectedVersion);
        validateRoleMutationState(program.getState(), request.role());

        UserEntity target = userRepository.findById(request.userId())
                .orElseThrow(ResourceNotFoundException::new);
        roleRepository.findRole(programId, request.userId()).ifPresent(existing -> {
            if (existing.getRole() == request.role()) {
                throw new ProgramRoleExistsException();
            }
            throw new RoleConflictException();
        });
        UserEntity assigner = userRepository.findById(actor.userId())
                .orElseThrow(ResourceNotFoundException::new);
        Instant assignedAt = clock.instant();
        ProgramRoleEntity assignment = new ProgramRoleEntity(
                program, target, request.role(), assignedAt, assigner);
        try {
            roleRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException exception) {
            throw new RoleConflictException();
        }
        auditLoggingService.recordUserAction(
                actor.userId(),
                "PROGRAM_ROLE_ADDED",
                "PROGRAM_ROLE",
                programId,
                Map.of(),
                roleSnapshot(assignment),
                null);
        incrementVersion(programId, expectedVersion);
        ProgramRoleResponse response = new ProgramRoleResponse(
                programId,
                target.getId(),
                target.getFullName(),
                request.role(),
                assignedAt,
                actor.userId(),
                expectedVersion + 1);
        return stored(201, response);
    }

    private ProgramEntity lockedProgram(UUID programId) {
        return programRepository.findByIdForUpdate(programId)
                .orElseThrow(ResourceNotFoundException::new);
    }

    private void incrementVersion(UUID programId, long expectedVersion) {
        if (programRepository.incrementVersion(programId, expectedVersion) != 1) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static void checkVersion(ProgramEntity program, long expectedVersion) {
        if (program.getVersion() != expectedVersion) {
            throw new OptimisticConcurrencyConflictException();
        }
    }

    private static void validateManagedRole(ProgramRoleType role) {
        if (role == null || role == ProgramRoleType.SUBMITTER) {
            throw new InvalidInputException(
                    "INVALID_PROGRAM_ROLE",
                    "Only PROGRAMMER or STAFF may be assigned through this endpoint.");
        }
    }

    private static void validateRoleMutationState(ProgramState state, ProgramRoleType role) {
        if (role == ProgramRoleType.STAFF && state != ProgramState.CREATED) {
            throw new InvalidStateException();
        }
        if (role == ProgramRoleType.PROGRAMMER && state == ProgramState.ANNOUNCED) {
            throw new InvalidStateException();
        }
    }

    private static ProgramDetails validateCreation(ProgramCreateRequest request) {
        if (request == null) {
            throw new InvalidInputException("VALIDATION_FAILED", "A request body is required.");
        }
        ProgramDetails details = new ProgramDetails(
                normalizeRequiredText(request.name(), "name", 255),
                normalizeRequiredText(request.description(), "description", Integer.MAX_VALUE),
                requiredDate(request.startDate(), "startDate"),
                requiredDate(request.endDate(), "endDate"));
        validateDetails(details);
        return details;
    }

    private static ProgramPatch validatePatch(ProgramUpdateRequest request) {
        if (request == null || !request.hasAnySuppliedField()) {
            throw new InvalidInputException(
                    "EMPTY_PROGRAM_UPDATE",
                    "At least one Program detail field must be supplied.");
        }
        String name = request.isNameSupplied()
                ? normalizeRequiredText(request.getName(), "name", 255) : null;
        String description = request.isDescriptionSupplied()
                ? normalizeRequiredText(request.getDescription(), "description", Integer.MAX_VALUE) : null;
        LocalDate startDate = request.isStartDateSupplied()
                ? requiredDate(request.getStartDate(), "startDate") : null;
        LocalDate endDate = request.isEndDateSupplied()
                ? requiredDate(request.getEndDate(), "endDate") : null;
        return new ProgramPatch(
                request.isNameSupplied(), name,
                request.isDescriptionSupplied(), description,
                request.isStartDateSupplied(), startDate,
                request.isEndDateSupplied(), endDate);
    }

    private static void validateDetails(ProgramDetails details) {
        if (details.endDate().isBefore(details.startDate())) {
            throw new InvalidInputException(
                    "INVALID_DATE_RANGE",
                    "endDate must be greater than or equal to startDate.");
        }
    }

    private static String normalizeRequiredText(String value, String field, int maximumLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidInputException("VALIDATION_FAILED", field + " must not be blank.");
        }
        String normalized = value.strip();
        if (normalized.length() > maximumLength) {
            throw new InvalidInputException("VALIDATION_FAILED", field + " is too long.");
        }
        return normalized;
    }

    private static LocalDate requiredDate(LocalDate value, String field) {
        if (value == null) {
            throw new InvalidInputException("VALIDATION_FAILED", field + " must be provided.");
        }
        return value;
    }

    private ProgramDetailResponse response(ProgramEntity program) {
        UserEntity creator = program.getCreator();
        return new ProgramDetailResponse(
                program.getId(),
                program.getName(),
                program.getDescription(),
                program.getStartDate(),
                program.getEndDate(),
                program.getState(),
                program.getCreatedAt(),
                program.getVersion(),
                new UserSummaryResponse(creator.getId(), creator.getUsername(), creator.getFullName()));
    }

    private static Map<String, ?> programSnapshot(ProgramEntity program) {
        return Map.of(
                "programId", program.getId(),
                "creatorUserId", program.getCreator().getId(),
                "name", program.getName(),
                "description", program.getDescription(),
                "startDate", program.getStartDate(),
                "endDate", program.getEndDate(),
                "state", program.getState(),
                "createdAt", program.getCreatedAt(),
                "version", program.getVersion());
    }

    private static Map<String, ?> roleSnapshot(ProgramRoleEntity role) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("programId", role.getProgram().getId());
        snapshot.put("userId", role.getUser().getId());
        snapshot.put("role", role.getRole());
        snapshot.put("assignedAt", role.getAssignedAt());
        snapshot.put("assignedByUserId",
                role.getAssignedBy() == null ? null : role.getAssignedBy().getId());
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

    private record ProgramDetails(String name, String description, LocalDate startDate, LocalDate endDate) {
    }

    private record ProgramPatch(
            boolean nameSupplied,
            String name,
            boolean descriptionSupplied,
            String description,
            boolean startDateSupplied,
            LocalDate startDate,
            boolean endDateSupplied,
            LocalDate endDate) {

        ProgramDetails applyTo(ProgramEntity program) {
            return new ProgramDetails(
                    nameSupplied ? name : program.getName(),
                    descriptionSupplied ? description : program.getDescription(),
                    startDateSupplied ? startDate : program.getStartDate(),
                    endDateSupplied ? endDate : program.getEndDate());
        }

        Map<String, Object> canonical(UUID programId, long expectedVersion) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("programId", programId);
            content.put("expectedVersion", expectedVersion);
            content.put("nameSupplied", nameSupplied);
            content.put("name", name);
            content.put("descriptionSupplied", descriptionSupplied);
            content.put("description", description);
            content.put("startDateSupplied", startDateSupplied);
            content.put("startDate", startDate);
            content.put("endDateSupplied", endDateSupplied);
            content.put("endDate", endDate);
            return content;
        }
    }
}
