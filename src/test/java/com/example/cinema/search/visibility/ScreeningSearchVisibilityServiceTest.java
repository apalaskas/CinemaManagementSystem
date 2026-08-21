package com.example.cinema.search.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.common.config.CinemaProperties;
import com.example.cinema.common.error.InvalidInputException;
import com.example.cinema.common.error.ResourceNotFoundException;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.program.repository.ProgramRepository;
import com.example.cinema.program.repository.ProgramRoleRepository;
import com.example.cinema.screening.api.FullScreeningResponse;
import com.example.cinema.screening.api.PublicScreeningResponse;
import com.example.cinema.screening.api.ScreeningSearchParameters;
import com.example.cinema.screening.api.ScreeningSearchView;
import com.example.cinema.screening.domain.ReviewEntity;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;
import com.example.cinema.screening.repository.ReviewRepository;
import com.example.cinema.screening.repository.ScreeningRepository;
import com.example.cinema.screening.repository.ScreeningSearchCriteria;
import com.example.cinema.screening.repository.ScreeningSearchPage;
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CurrentUser;
import com.example.cinema.user.domain.UserEntity;

@ExtendWith(MockitoExtension.class)
class ScreeningSearchVisibilityServiceTest {

    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OTHER_PROGRAM_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
    private static final UUID SCREENING_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID PUBLIC_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
    private static final UUID REQUESTER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Mock ProgramRepository programRepository;
    @Mock ProgramRoleRepository roleRepository;
    @Mock ScreeningRepository screeningRepository;
    @Mock ReviewRepository reviewRepository;
    @Mock CurrentUser currentUser;

    private SearchAndVisibilityService service;
    private UserEntity requester;
    private UserEntity other;
    private ProgramEntity program;

    @BeforeEach
    void setUp() {
        requester = new UserEntity(REQUESTER_ID, "alice", "hash-never-exposed", "Alice User");
        other = new UserEntity(OTHER_ID, "bob", "hash-never-exposed", "Bob User");
        program = new ProgramEntity(PROGRAM_ID, other, "Festival", "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-01"), NOW);
        CinemaProperties.Policy policy = new CinemaProperties.Policy(100, Duration.ofMinutes(1));
        CinemaProperties properties = new CinemaProperties(
                new CinemaProperties.Pagination(20, 100),
                new CinemaProperties.RateLimit(policy, policy, policy, policy, 1000, Duration.ofMinutes(5)),
                new CinemaProperties.Idempotency(Duration.ofHours(24)));
        service = new SearchAndVisibilityService(programRepository, roleRepository,
                screeningRepository, reviewRepository, currentUser, properties);
    }

    @Test
    void normalizesMultiwordFiltersAndDefaultsViewPageAndSize() {
        announce(program);
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.searchVisible(eq(PROGRAM_ID), any(), eq(null), eq(null), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(), 0));

        service.searchScreenings(PROGRAM_ID, new ScreeningSearchParameters(
                "  Dark   NIGHT ", " Alice\tBOB ", "  ", null, null, null, 0, null));

        ArgumentCaptor<ScreeningSearchCriteria> criteria = ArgumentCaptor.forClass(ScreeningSearchCriteria.class);
        verify(screeningRepository).searchVisible(
                eq(PROGRAM_ID), criteria.capture(), eq(null), eq(null), eq(0), eq(20));
        assertThat(criteria.getValue().filmTitleWords()).containsExactly("dark", "night");
        assertThat(criteria.getValue().castWords()).containsExactly("alice", "bob");
        assertThat(criteria.getValue().genreWords()).isEmpty();
        assertThat(criteria.getValue().view()).isEqualTo(ScreeningSearchView.GENERAL);
    }

    @Test
    void rejectsInvalidRangePaginationAndView() {
        assertInvalid(parameters(NOW.plusSeconds(1), NOW, null, 0, null), "INVALID_DATE_RANGE");
        assertInvalid(parameters(null, null, null, -1, null), "INVALID_PAGE");
        assertInvalid(parameters(null, null, null, 0, 0), "INVALID_PAGE_SIZE");
        assertInvalid(parameters(null, null, null, 0, 101), "INVALID_PAGE_SIZE");
        assertInvalid(parameters(null, null, "general", 0, null), "INVALID_SCREENING_VIEW");
    }

    @Test
    void anonymousAndOrdinaryUsersReceiveOnlyPublicScheduledProjection() {
        announce(program);
        ScreeningEntity scheduled = scheduled(PUBLIC_ID, program, other, other);
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(screeningRepository.searchVisible(eq(PROGRAM_ID), any(), eq(null), eq(null), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(scheduled), 1));

        var anonymous = service.searchScreenings(PROGRAM_ID, parameters(null, null, null, 0, null));

        assertThat(anonymous.totalElements()).isEqualTo(1);
        assertThat(anonymous.content()).singleElement().isInstanceOfSatisfying(
                PublicScreeningResponse.class, response -> {
                    assertThat(response.programId()).isEqualTo(PROGRAM_ID);
                    assertThat(response.finalAuditoriumName()).isEqualTo("Main Hall");
                    assertThat(response.filmTitle()).isEqualTo("Dark Night");
                });
        verify(reviewRepository, never()).findAllWithStaffByScreeningIds(any());

        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID)).thenReturn(Optional.empty());
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(null), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(scheduled), 1));
        assertThat(service.searchScreenings(PROGRAM_ID, parameters(null, null, null, 0, null)).content())
                .singleElement().isInstanceOf(PublicScreeningResponse.class);
    }

    @Test
    void programmerGetsFullRowsOnlyFromTheSelectedProgram() {
        ScreeningEntity draft = completeDraft(SCREENING_ID, program, other);
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID))
                .thenReturn(Optional.of(role(ProgramRoleType.PROGRAMMER)));
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.PROGRAMMER), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(draft), 1));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID))).thenReturn(List.of());

        var response = service.searchScreenings(PROGRAM_ID, parameters(null, null, null, 0, null));

        assertThat(response.content()).singleElement().isInstanceOfSatisfying(
                FullScreeningResponse.class, full -> {
                    assertThat(full.programId()).isEqualTo(PROGRAM_ID);
                    assertThat(full.submitter().username()).isEqualTo("bob");
                    assertThat(full.candidateAuditoriumName()).isEqualTo("Candidate Hall");
                    assertThat(full.version()).isZero();
                });
        verify(roleRepository).findRole(PROGRAM_ID, REQUESTER_ID);
    }

    @Test
    void staffGetsMixedAssignedFullAndUnassignedPublicRows() {
        announce(program);
        ScreeningEntity assigned = completeDraft(SCREENING_ID, program, other);
        assigned.submit();
        assigned.assignHandler(requester);
        ScreeningEntity publicRow = scheduled(PUBLIC_ID, program, other, other);
        stubRole(ProgramRoleType.STAFF);
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.STAFF), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(assigned, publicRow), 2));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID))).thenReturn(List.of());

        var content = service.searchScreenings(
                PROGRAM_ID, parameters(null, null, "TIMETABLE", 0, null)).content();

        assertThat(content.get(0)).isInstanceOf(FullScreeningResponse.class);
        assertThat(content.get(1)).isInstanceOf(PublicScreeningResponse.class);
    }

    @Test
    void assignedStaffSeesReviewBeforeAProgrammerDecision() {
        ScreeningEntity assigned = completeDraft(SCREENING_ID, program, other);
        assigned.submit();
        assigned.assignHandler(requester);
        assigned.markReviewed();
        ReviewEntity review = new ReviewEntity(UUID.randomUUID(), assigned, requester,
                new BigDecimal("8.50"), "Detailed comments", NOW);
        stubRole(ProgramRoleType.STAFF);
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.STAFF), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(assigned), 1));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID)))
                .thenReturn(List.of(review));

        assertThat(service.searchScreenings(
                PROGRAM_ID, parameters(null, null, null, 0, null)).content())
                .singleElement().isInstanceOfSatisfying(
                        FullScreeningResponse.class,
                        full -> assertThat(full.review().numericScore()).isEqualByComparingTo("8.50"));
    }

    @Test
    void submitterGetsMixedOwnedFullAndPublicAndReviewOnlyAfterDecision() {
        announce(program);
        ScreeningEntity owned = completeDraft(SCREENING_ID, program, requester);
        owned.submit();
        owned.assignHandler(other);
        owned.markReviewed();
        ReviewEntity review = new ReviewEntity(UUID.randomUUID(), owned, other,
                new BigDecimal("8.50"), "Detailed comments", NOW);
        ScreeningEntity publicRow = scheduled(PUBLIC_ID, program, other, other);
        stubRole(ProgramRoleType.SUBMITTER);
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.SUBMITTER), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(owned, publicRow), 2));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID)))
                .thenReturn(List.of(review));

        var before = service.searchScreenings(PROGRAM_ID, parameters(null, null, null, 0, null)).content();
        assertThat(before.get(0)).isInstanceOfSatisfying(
                FullScreeningResponse.class, full -> assertThat(full.review()).isNull());
        assertThat(before.get(1)).isInstanceOf(PublicScreeningResponse.class);

        owned.approve("Changes requested");
        var after = service.searchScreenings(PROGRAM_ID, parameters(null, null, null, 0, null)).content();
        assertThat(after.get(0)).isInstanceOfSatisfying(
                FullScreeningResponse.class, full -> {
                    assertThat(full.review().numericScore()).isEqualByComparingTo("8.50");
                    assertThat(full.review().detailedComments()).isEqualTo("Detailed comments");
                });
    }

    @Test
    void submitterReviewAlsoBecomesVisibleAfterRejection() {
        ScreeningEntity owned = completeDraft(SCREENING_ID, program, requester);
        owned.submit();
        owned.assignHandler(other);
        owned.markReviewed();
        ReviewEntity review = new ReviewEntity(UUID.randomUUID(), owned, other,
                new BigDecimal("4.25"), "Not ready", NOW);
        owned.rejectReviewed("Insufficient quality");
        stubRole(ProgramRoleType.SUBMITTER);
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.SUBMITTER), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(owned), 1));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID)))
                .thenReturn(List.of(review));

        assertThat(service.searchScreenings(
                PROGRAM_ID, parameters(null, null, null, 0, null)).content())
                .singleElement().isInstanceOfSatisfying(
                        FullScreeningResponse.class,
                        full -> assertThat(full.review().numericScore()).isEqualByComparingTo("4.25"));
    }

    @Test
    void projectionFailsClosedBeforeReviewLoadingForARowFromAnotherProgram() {
        ProgramEntity otherProgram = new ProgramEntity(OTHER_PROGRAM_ID, other, "Other Festival", "Description",
                LocalDate.parse("2027-03-01"), LocalDate.parse("2027-04-01"), NOW);
        ScreeningEntity foreignDraft = completeDraft(SCREENING_ID, otherProgram, other);
        stubRole(ProgramRoleType.PROGRAMMER);
        when(screeningRepository.searchVisible(
                eq(PROGRAM_ID), any(), eq(REQUESTER_ID), eq(ProgramRoleType.PROGRAMMER), eq(0), eq(20)))
                .thenReturn(new ScreeningSearchPage(List.of(foreignDraft), 1));

        assertThatThrownBy(() -> service.searchScreenings(
                PROGRAM_ID, parameters(null, null, null, 0, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(reviewRepository, never()).findAllWithStaffByScreeningIds(any());
    }

    @Test
    void hiddenProgramCollectionAndHiddenOrDeletedDirectViewsUseSafeNotFound() {
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        assertThatThrownBy(() -> service.searchScreenings(
                PROGRAM_ID, parameters(null, null, null, 0, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(PROGRAM_ID.toString());
        verify(screeningRepository, never()).searchVisible(any(), any(), any(), any(), any(Integer.class), any(Integer.class));

        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(screeningRepository.findVisibleDetail(SCREENING_ID, null, null)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewScreening(SCREENING_ID))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining(SCREENING_ID.toString());

        when(screeningRepository.findActiveProgramIdById(PUBLIC_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewScreening(PUBLIC_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void programmerRoleInAnotherProgramDoesNotGrantAccessToSelectedPrivateProgram() {
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID)).thenReturn(Optional.empty());
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));

        assertThatThrownBy(() -> service.searchScreenings(
                PROGRAM_ID, parameters(null, null, null, 0, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(roleRepository).findRole(PROGRAM_ID, REQUESTER_ID);
        verify(screeningRepository, never()).searchVisible(
                any(), any(), any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void anonymousDirectViewOfAnnouncedScheduledScreeningIsPublic() {
        announce(program);
        ScreeningEntity screening = scheduled(SCREENING_ID, program, other, other);
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(screeningRepository.findVisibleDetail(SCREENING_ID, null, null))
                .thenReturn(Optional.of(screening));

        assertThat(service.viewScreening(SCREENING_ID)).isInstanceOf(PublicScreeningResponse.class);
        verify(reviewRepository, never()).findAllWithStaffByScreeningIds(any());
    }

    @Test
    void ordinaryAuthenticatedDirectViewUsesTheVisitorProjection() {
        announce(program);
        ScreeningEntity screening = scheduled(SCREENING_ID, program, other, other);
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID)).thenReturn(Optional.empty());
        when(screeningRepository.findVisibleDetail(SCREENING_ID, REQUESTER_ID, null))
                .thenReturn(Optional.of(screening));

        assertThat(service.viewScreening(SCREENING_ID)).isInstanceOfSatisfying(
                PublicScreeningResponse.class,
                response -> assertThat(response.programId()).isEqualTo(PROGRAM_ID));
        verify(reviewRepository, never()).findAllWithStaffByScreeningIds(any());
    }

    @Test
    void directViewUsesTheSameProgramSpecificRoleAndProjection() {
        ScreeningEntity draft = completeDraft(SCREENING_ID, program, other);
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(screeningRepository.findActiveProgramIdById(SCREENING_ID)).thenReturn(Optional.of(PROGRAM_ID));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID))
                .thenReturn(Optional.of(role(ProgramRoleType.PROGRAMMER)));
        when(screeningRepository.findVisibleDetail(
                SCREENING_ID, REQUESTER_ID, ProgramRoleType.PROGRAMMER)).thenReturn(Optional.of(draft));
        when(reviewRepository.findAllWithStaffByScreeningIds(List.of(SCREENING_ID))).thenReturn(List.of());

        assertThat(service.viewScreening(SCREENING_ID)).isInstanceOf(FullScreeningResponse.class);
        verify(roleRepository).findRole(PROGRAM_ID, REQUESTER_ID);
    }

    @Test
    void screeningSearchAndViewAreReadOnlyTransactions() throws Exception {
        assertThat(SearchAndVisibilityService.class
                .getMethod("searchScreenings", UUID.class, ScreeningSearchParameters.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(SearchAndVisibilityService.class
                .getMethod("viewScreening", UUID.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private void stubRole(ProgramRoleType role) {
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.findById(PROGRAM_ID)).thenReturn(Optional.of(program));
        when(roleRepository.findRole(PROGRAM_ID, REQUESTER_ID)).thenReturn(Optional.of(role(role)));
    }

    private ProgramRoleEntity role(ProgramRoleType role) {
        return new ProgramRoleEntity(program, requester, role, NOW, requester);
    }

    private void assertInvalid(ScreeningSearchParameters parameters, String errorCode) {
        assertThatThrownBy(() -> service.searchScreenings(PROGRAM_ID, parameters))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }

    private static ScreeningSearchParameters parameters(
            Instant from, Instant to, String view, int page, Integer size) {
        return new ScreeningSearchParameters(null, null, null, from, to, view, page, size);
    }

    private static ScreeningEntity completeDraft(
            UUID id, ProgramEntity program, UserEntity submitter) {
        return new ScreeningEntity(id, program, submitter, "Dark Night", "Alice Bob", "Drama",
                90, "Candidate Hall", Instant.parse("2027-01-10T10:00:00Z"),
                Instant.parse("2027-01-10T12:00:00Z"), NOW);
    }

    private static ScreeningEntity scheduled(
            UUID id, ProgramEntity program, UserEntity submitter, UserEntity handler) {
        ScreeningEntity screening = completeDraft(id, program, submitter);
        screening.submit();
        screening.assignHandler(handler);
        screening.markReviewed();
        screening.approve(null);
        screening.recordFinalSubmission("Dark Night", "Alice Bob", "Drama", 90, "Candidate Hall",
                Instant.parse("2027-01-10T10:00:00Z"), Instant.parse("2027-01-10T12:00:00Z"), NOW);
        screening.schedule("Main Hall", Instant.parse("2027-01-10T10:00:00Z"),
                Instant.parse("2027-01-10T12:00:00Z"));
        return screening;
    }

    private static void announce(ProgramEntity program) {
        while (program.getState() != ProgramState.ANNOUNCED) {
            program.transitionTo(program.getState().next().orElseThrow());
        }
    }

    private static AuthenticatedUserIdentity identity() {
        return new AuthenticatedUserIdentity(REQUESTER_ID, "alice", "Alice User");
    }
}
