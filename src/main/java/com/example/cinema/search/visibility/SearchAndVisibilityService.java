package com.example.cinema.search.visibility;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.common.api.PageResponse;
import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.program.api.FullProgramResponse;
import com.example.cinema.program.api.ProgramRoleSummaryResponse;
import com.example.cinema.program.api.ProgramScreeningSummaryResponse;
import com.example.cinema.program.api.ProgramSearchParameters;
import com.example.cinema.program.api.ProgramSortDirection;
import com.example.cinema.program.api.ProgramViewResponse;
import com.example.cinema.program.api.PublicProgramResponse;
import com.example.cinema.program.api.UserSummaryResponse;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.program.repository.ProgramSearchCriteria;
import com.example.cinema.program.repository.ProgramSearchPage;
import com.example.cinema.program.repository.ProgrammerNameProjection;
import com.example.cinema.screening.repository.ProgramAuditoriumProjection;
import com.example.cinema.screening.repository.ProgramScreeningCountProjection;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.screening.api.FullScreeningResponse;
import com.example.cinema.screening.api.PublicScreeningResponse;
import com.example.cinema.screening.api.ScreeningReviewDetailResponse;
import com.example.cinema.screening.api.ScreeningSearchParameters;
import com.example.cinema.screening.api.ScreeningSearchView;
import com.example.cinema.screening.api.ScreeningViewResponse;
import com.example.cinema.screening.domain.ReviewEntity;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ReviewRepository;
import com.example.cinema.screening.repository.ScreeningSearchCriteria;
import com.example.cinema.screening.repository.ScreeningSearchPage;
import com.example.cinema.user.authentication.CurrentUser;
import com.example.cinema.user.domain.UserEntity;

@Service
public class SearchAndVisibilityService {

    private final ProgramRepository programRepository;
    private final ProgramRoleRepository roleRepository;
    private final ScreeningRepository screeningRepository;
    private final ReviewRepository reviewRepository;
    private final CurrentUser currentUser;
    private final CinemaProperties.Pagination pagination;

    public SearchAndVisibilityService(
            ProgramRepository programRepository,
            ProgramRoleRepository roleRepository,
            ScreeningRepository screeningRepository,
            ReviewRepository reviewRepository,
            CurrentUser currentUser,
            CinemaProperties properties) {
        this.programRepository = programRepository;
        this.roleRepository = roleRepository;
        this.screeningRepository = screeningRepository;
        this.reviewRepository = reviewRepository;
        this.currentUser = currentUser;
        this.pagination = properties.pagination();
    }

    @Transactional(readOnly = true)
    public PageResponse<ProgramViewResponse> searchPrograms(ProgramSearchParameters parameters) {
        ValidatedSearch search = validate(parameters);
        UUID requesterUserId = currentUser.optional().map(identity -> identity.userId()).orElse(null);
        ProgramSearchPage page = programRepository.searchVisible(
                search.criteria(), requesterUserId, search.page(), search.size());
        List<ProgramViewResponse> content = project(page.programs(), requesterUserId);
        long pages = page.totalElements() == 0
                ? 0
                : ((page.totalElements() - 1) / search.size()) + 1;
        return new PageResponse<>(search.page(), search.size(), page.totalElements(),
                (int) Math.min(Integer.MAX_VALUE, pages), content);
    }

    @Transactional(readOnly = true)
    public ProgramViewResponse viewProgram(UUID programId) {
        UUID requesterUserId = currentUser.optional().map(identity -> identity.userId()).orElse(null);
        ProgramEntity program = programRepository.findVisibleById(programId, requesterUserId)
                .orElseThrow(ResourceNotFoundException::new);
        return project(List.of(program), requesterUserId).getFirst();
    }

    @Transactional(readOnly = true)
    public PageResponse<ScreeningViewResponse> searchScreenings(
            UUID programId, ScreeningSearchParameters parameters) {
        ValidatedScreeningSearch search = validateScreeningSearch(parameters);
        UUID requesterUserId = currentUser.optional().map(identity -> identity.userId()).orElse(null);
        ProgramRoleType requesterRole = roleFor(programId, requesterUserId);
        ProgramEntity program = programRepository.findById(programId)
                .orElseThrow(ResourceNotFoundException::new);
        if (requesterRole == null && program.getState() != ProgramState.ANNOUNCED) {
            throw new ResourceNotFoundException();
        }
        ScreeningSearchPage page = screeningRepository.searchVisible(
                programId, search.criteria(), requesterUserId, requesterRole, search.page(), search.size());
        List<ScreeningViewResponse> content = projectScreenings(
                page.screenings(), programId, requesterUserId, requesterRole);
        long pages = page.totalElements() == 0 ? 0 : ((page.totalElements() - 1) / search.size()) + 1;
        return new PageResponse<>(search.page(), search.size(), page.totalElements(),
                (int) Math.min(Integer.MAX_VALUE, pages), content);
    }

    @Transactional(readOnly = true)
    public ScreeningViewResponse viewScreening(UUID screeningId) {
        UUID requesterUserId = currentUser.optional().map(identity -> identity.userId()).orElse(null);
        UUID programId = screeningRepository.findActiveProgramIdById(screeningId)
                .orElseThrow(ResourceNotFoundException::new);
        ProgramRoleType requesterRole = roleFor(programId, requesterUserId);
        ScreeningEntity screening = screeningRepository.findVisibleDetail(
                screeningId, requesterUserId, requesterRole)
                .orElseThrow(ResourceNotFoundException::new);
        return projectScreenings(List.of(screening), programId, requesterUserId, requesterRole).getFirst();
    }

    private ProgramRoleType roleFor(UUID programId, UUID requesterUserId) {
        if (requesterUserId == null) {
            return null;
        }
        return roleRepository.findRole(programId, requesterUserId)
                .map(ProgramRoleEntity::getRole).orElse(null);
    }

    private List<ScreeningViewResponse> projectScreenings(
            List<ScreeningEntity> screenings,
            UUID authorizedProgramId,
            UUID requesterUserId,
            ProgramRoleType requesterRole) {
        if (screenings.isEmpty()) {
            return List.of();
        }
        boolean containsUnavailableScreening = screenings.stream().anyMatch(screening ->
                screening.getDeletedAt() != null
                        || !authorizedProgramId.equals(screening.getProgram().getId()));
        if (containsUnavailableScreening) {
            throw new ResourceNotFoundException();
        }
        List<UUID> fullIds = screenings.stream()
                .filter(screening -> isFull(screening, requesterUserId, requesterRole))
                .map(ScreeningEntity::getId).toList();
        Map<UUID, ReviewEntity> reviews = new HashMap<>();
        if (!fullIds.isEmpty()) {
            reviewRepository.findAllWithStaffByScreeningIds(fullIds)
                    .forEach(review -> reviews.put(review.getScreening().getId(), review));
        }
        List<ScreeningViewResponse> result = new ArrayList<>(screenings.size());
        for (ScreeningEntity screening : screenings) {
            if (isFull(screening, requesterUserId, requesterRole)) {
                result.add(fullScreening(screening, reviews.get(screening.getId()), requesterRole));
            } else if (isPublic(screening)) {
                result.add(new PublicScreeningResponse(
                        screening.getId(), screening.getProgram().getId(),
                        screening.getFilmTitle(), screening.getGenre(),
                        screening.getStartTime(), screening.getEndTime(), screening.getFinalAuditoriumName()));
            } else {
                throw new ResourceNotFoundException();
            }
        }
        return List.copyOf(result);
    }

    private static boolean isFull(
            ScreeningEntity screening, UUID requesterUserId, ProgramRoleType requesterRole) {
        if (requesterUserId == null || requesterRole == null) {
            return false;
        }
        return requesterRole == ProgramRoleType.PROGRAMMER
                || requesterRole == ProgramRoleType.STAFF
                        && screening.getHandler() != null
                        && requesterUserId.equals(screening.getHandler().getId())
                || requesterRole == ProgramRoleType.SUBMITTER
                        && requesterUserId.equals(screening.getSubmitter().getId());
    }

    private static boolean isPublic(ScreeningEntity screening) {
        return screening.getProgram().getState() == ProgramState.ANNOUNCED
                && screening.getState() == ScreeningState.SCHEDULED;
    }

    private static FullScreeningResponse fullScreening(
            ScreeningEntity screening, ReviewEntity review, ProgramRoleType requesterRole) {
        UserEntity handler = screening.getHandler();
        boolean ownerBeforeDecision = requesterRole == ProgramRoleType.SUBMITTER
                && !reviewVisibleToOwner(screening.getState());
        return new FullScreeningResponse(
                screening.getId(), screening.getProgram().getId(), screening.getFilmTitle(),
                screening.getCastText(), screening.getGenre(), screening.getDurationMinutes(),
                screening.getCandidateAuditoriumName(), screening.getFinalAuditoriumName(),
                screening.getStartTime(), screening.getEndTime(), screening.getState(),
                screening.getConditionalNotes(), screening.getFinalSubmittedAt(),
                screening.getRejectionReason(), userSummary(screening.getSubmitter()),
                handler == null ? null : userSummary(handler),
                review == null || ownerBeforeDecision ? null : reviewSummary(review),
                screening.getCreatedAt(), screening.getVersion());
    }

    private static boolean reviewVisibleToOwner(ScreeningState state) {
        return state == ScreeningState.APPROVED
                || state == ScreeningState.REJECTED
                || state == ScreeningState.SCHEDULED;
    }

    private static ScreeningReviewDetailResponse reviewSummary(ReviewEntity review) {
        return new ScreeningReviewDetailResponse(
                review.getId(), review.getNumericScore(), review.getDetailedComments(),
                userSummary(review.getStaff()), review.getCreatedAt());
    }

    private static UserSummaryResponse userSummary(UserEntity user) {
        return new UserSummaryResponse(user.getId(), user.getUsername(), user.getFullName());
    }

    private ValidatedScreeningSearch validateScreeningSearch(ScreeningSearchParameters parameters) {
        if (parameters == null) {
            throw new InvalidInputException("INVALID_SEARCH", "Search parameters are required.");
        }
        if (parameters.page() < 0) {
            throw new InvalidInputException("INVALID_PAGE", "page must be greater than or equal to zero.");
        }
        int maximumSize = Math.min(100, pagination.maxSize());
        int size = parameters.size() == null ? pagination.defaultSize() : parameters.size();
        if (size < 1 || size > maximumSize) {
            throw new InvalidInputException(
                    "INVALID_PAGE_SIZE", "size must be between 1 and " + maximumSize + ".");
        }
        if (parameters.fromDateTime() != null && parameters.toDateTime() != null
                && parameters.fromDateTime().isAfter(parameters.toDateTime())) {
            throw new InvalidInputException(
                    "INVALID_DATE_RANGE", "fromDateTime must be before or equal to toDateTime.");
        }
        ScreeningSearchView view = parseScreeningView(parameters.view());
        ScreeningSearchCriteria criteria = new ScreeningSearchCriteria(
                words(parameters.filmTitle()), words(parameters.cast()), words(parameters.genre()),
                parameters.fromDateTime(), parameters.toDateTime(), view);
        return new ValidatedScreeningSearch(criteria, parameters.page(), size);
    }

    private static ScreeningSearchView parseScreeningView(String value) {
        if (value == null || value.isBlank()) {
            return ScreeningSearchView.GENERAL;
        }
        try {
            return ScreeningSearchView.valueOf(value.strip());
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException("INVALID_SCREENING_VIEW", "view must be GENERAL or TIMETABLE.");
        }
    }

    private static List<String> words(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.strip().toLowerCase(Locale.ROOT).split("\\s+"))
                .filter(word -> !word.isEmpty()).toList();
    }

    private List<ProgramViewResponse> project(List<ProgramEntity> programs, UUID requesterUserId) {
        if (programs.isEmpty()) {
            return List.of();
        }
        List<UUID> programIds = programs.stream().map(ProgramEntity::getId).toList();
        Set<UUID> managedProgramIds = requesterUserId == null
                ? Set.of()
                : Set.copyOf(roleRepository.findProgramIdsForUserRole(
                        programIds, requesterUserId, ProgramRoleType.PROGRAMMER));
        boolean containsConcealedProgram = programs.stream().anyMatch(program ->
                program.getState() != ProgramState.ANNOUNCED
                        && !managedProgramIds.contains(program.getId()));
        if (containsConcealedProgram) {
            throw new ResourceNotFoundException();
        }

        Map<UUID, List<String>> programmerNames = groupProgrammerNames(
                roleRepository.findProgrammerNames(programIds));
        Map<UUID, List<String>> auditoriumNames = groupAuditoriums(
                screeningRepository.findDistinctScheduledAuditoriums(programIds));

        List<UUID> managedIds = programIds.stream().filter(managedProgramIds::contains).toList();
        Map<UUID, List<ProgramRoleSummaryResponse>> roles = managedIds.isEmpty()
                ? Map.of()
                : groupRoles(roleRepository.findAllWithUsersByProgramIds(managedIds));
        Map<UUID, ProgramScreeningSummaryResponse> screeningSummaries = managedIds.isEmpty()
                ? Map.of()
                : screeningSummaries(screeningRepository.countActiveAndScheduledByProgramIds(managedIds));

        List<ProgramViewResponse> result = new ArrayList<>(programs.size());
        for (ProgramEntity program : programs) {
            List<String> names = programmerNames.getOrDefault(program.getId(), List.of());
            List<String> auditoriums = auditoriumNames.getOrDefault(program.getId(), List.of());
            if (managedProgramIds.contains(program.getId())) {
                UserEntity creator = program.getCreator();
                result.add(new FullProgramResponse(
                        program.getId(), program.getName(), program.getDescription(),
                        program.getStartDate(), program.getEndDate(), names, auditoriums,
                        program.getState(), program.getCreatedAt(), program.getVersion(),
                        new UserSummaryResponse(
                                creator.getId(), creator.getUsername(), creator.getFullName()),
                        roles.getOrDefault(program.getId(), List.of()),
                        screeningSummaries.getOrDefault(program.getId(), emptyScreeningSummary(program.getId()))));
            } else {
                result.add(new PublicProgramResponse(
                        program.getId(), program.getName(), program.getDescription(),
                        program.getStartDate(), program.getEndDate(), names, auditoriums));
            }
        }
        return List.copyOf(result);
    }

    private static Map<UUID, List<String>> groupProgrammerNames(List<ProgrammerNameProjection> rows) {
        Map<UUID, List<String>> grouped = new HashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getProgramId(), ignored -> new ArrayList<>())
                .add(row.getFullName()));
        return immutableLists(grouped);
    }

    private static Map<UUID, List<String>> groupAuditoriums(List<ProgramAuditoriumProjection> rows) {
        Map<UUID, LinkedHashSet<String>> grouped = new HashMap<>();
        rows.forEach(row -> grouped.computeIfAbsent(row.getProgramId(), ignored -> new LinkedHashSet<>())
                .add(row.getAuditoriumName()));
        Map<UUID, List<String>> result = new HashMap<>();
        grouped.forEach((programId, values) -> result.put(programId, List.copyOf(values)));
        return Map.copyOf(result);
    }

    private static Map<UUID, List<ProgramRoleSummaryResponse>> groupRoles(List<ProgramRoleEntity> assignments) {
        Map<UUID, List<ProgramRoleSummaryResponse>> grouped = new HashMap<>();
        assignments.forEach(assignment -> grouped
                .computeIfAbsent(assignment.getProgram().getId(), ignored -> new ArrayList<>())
                .add(new ProgramRoleSummaryResponse(
                        assignment.getUser().getId(),
                        assignment.getUser().getUsername(),
                        assignment.getUser().getFullName(),
                        assignment.getRole(),
                        assignment.getAssignedAt(),
                        assignment.getAssignedBy() == null ? null : assignment.getAssignedBy().getId())));
        return immutableLists(grouped);
    }

    private static Map<UUID, ProgramScreeningSummaryResponse> screeningSummaries(
            List<ProgramScreeningCountProjection> rows) {
        Map<UUID, ProgramScreeningSummaryResponse> summaries = new HashMap<>();
        rows.forEach(row -> summaries.put(row.getProgramId(), new ProgramScreeningSummaryResponse(
                row.getActiveCount(), row.getScheduledCount(), collectionUrl(row.getProgramId()))));
        return Map.copyOf(summaries);
    }

    private static ProgramScreeningSummaryResponse emptyScreeningSummary(UUID programId) {
        return new ProgramScreeningSummaryResponse(0, 0, collectionUrl(programId));
    }

    private static String collectionUrl(UUID programId) {
        return "/api/v1/programs/" + programId + "/screenings";
    }

    private static <T> Map<UUID, List<T>> immutableLists(Map<UUID, List<T>> mutable) {
        Map<UUID, List<T>> result = new HashMap<>();
        mutable.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return Collections.unmodifiableMap(result);
    }

    private ValidatedSearch validate(ProgramSearchParameters parameters) {
        if (parameters == null) {
            throw new InvalidInputException("INVALID_SEARCH", "Search parameters are required.");
        }
        if (parameters.page() < 0) {
            throw new InvalidInputException("INVALID_PAGE", "page must be greater than or equal to zero.");
        }
        int size = parameters.size() == null ? pagination.defaultSize() : parameters.size();
        if (size < 1 || size > pagination.maxSize()) {
            throw new InvalidInputException(
                    "INVALID_PAGE_SIZE",
                    "size must be between 1 and " + pagination.maxSize() + ".");
        }
        if (parameters.fromDate() != null && parameters.toDate() != null
                && parameters.fromDate().isAfter(parameters.toDate())) {
            throw new InvalidInputException(
                    "INVALID_DATE_RANGE", "fromDate must be before or equal to toDate.");
        }
        ProgramSortDirection direction = parseDirection(parameters.direction());
        ProgramSearchCriteria criteria = new ProgramSearchCriteria(
                normalizeFilter(parameters.name()),
                normalizeFilter(parameters.description()),
                parameters.fromDate(),
                parameters.toDate(),
                normalizeFilter(parameters.filmTitle()),
                normalizeFilter(parameters.auditorium()),
                direction);
        return new ValidatedSearch(criteria, parameters.page(), size);
    }

    private static ProgramSortDirection parseDirection(String value) {
        if (value == null) {
            return ProgramSortDirection.ASC;
        }
        try {
            return ProgramSortDirection.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new InvalidInputException(
                    "INVALID_SORT_DIRECTION", "direction must be ASC or DESC.");
        }
    }

    private static String normalizeFilter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private record ValidatedSearch(ProgramSearchCriteria criteria, int page, int size) {
    }

    private record ValidatedScreeningSearch(ScreeningSearchCriteria criteria, int page, int size) {
    }
}
