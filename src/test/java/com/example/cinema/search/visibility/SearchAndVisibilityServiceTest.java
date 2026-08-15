package com.example.cinema.search.visibility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.example.cinema.program.api.FullProgramResponse;
import com.example.cinema.program.api.ProgramSearchParameters;
import com.example.cinema.program.api.ProgramSortDirection;
import com.example.cinema.program.api.PublicProgramResponse;
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
import com.example.cinema.user.authentication.AuthenticatedUserIdentity;
import com.example.cinema.user.authentication.CurrentUser;
import com.example.cinema.user.domain.UserEntity;

@ExtendWith(MockitoExtension.class)
class SearchAndVisibilityServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID PUBLIC_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MANAGED_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final Instant NOW = Instant.parse("2026-08-15T09:00:00Z");

    @Mock ProgramRepository programRepository;
    @Mock ProgramRoleRepository roleRepository;
    @Mock ScreeningRepository screeningRepository;
    @Mock CurrentUser currentUser;

    SearchAndVisibilityService service;
    UserEntity user;

    @BeforeEach
    void setUp() {
        user = new UserEntity(USER_ID, "alice", "hash-never-exposed", "Alice Programmer");
        CinemaProperties.Policy policy = new CinemaProperties.Policy(100, Duration.ofMinutes(1));
        CinemaProperties properties = new CinemaProperties(
                new CinemaProperties.Pagination(20, 100),
                new CinemaProperties.RateLimit(policy, policy, policy, policy, 1000, Duration.ofMinutes(5)),
                new CinemaProperties.Idempotency(Duration.ofHours(24)));
        service = new SearchAndVisibilityService(
                programRepository, roleRepository, screeningRepository, currentUser, properties);
    }

    @Test
    void normalizesBlankFiltersAndAppliesNoFilterDefaults() {
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.searchVisible(any(), eq(null), eq(0), eq(20)))
                .thenReturn(new ProgramSearchPage(List.of(), 0));

        var response = service.searchPrograms(new ProgramSearchParameters(
                "  ", "\t", null, null, "", null, null, 0, null));

        ArgumentCaptor<ProgramSearchCriteria> criteria = ArgumentCaptor.forClass(ProgramSearchCriteria.class);
        verify(programRepository).searchVisible(criteria.capture(), eq(null), eq(0), eq(20));
        assertThat(criteria.getValue()).isEqualTo(new ProgramSearchCriteria(
                null, null, null, null, null, null, ProgramSortDirection.ASC));
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.totalPages()).isZero();
        assertThat(response.content()).isEmpty();
    }

    @Test
    void trimsAndNormalizesEveryTextFilterAndPreservesOverlapDates() {
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.searchVisible(any(), eq(null), eq(2), eq(10)))
                .thenReturn(new ProgramSearchPage(List.of(), 21));

        var response = service.searchPrograms(new ProgramSearchParameters(
                " FeST ", " ARCHIVE ", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-12-31"), " FILM ", " HALL ", "DESC", 2, 10));

        ArgumentCaptor<ProgramSearchCriteria> criteria = ArgumentCaptor.forClass(ProgramSearchCriteria.class);
        verify(programRepository).searchVisible(criteria.capture(), eq(null), eq(2), eq(10));
        assertThat(criteria.getValue()).isEqualTo(new ProgramSearchCriteria(
                "fest", "archive", LocalDate.parse("2027-01-01"),
                LocalDate.parse("2027-12-31"), "film", "hall", ProgramSortDirection.DESC));
        assertThat(response.totalPages()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidDatePageSizeAndDirectionBeforeQuerying() {
        assertInvalid(parameters(LocalDate.parse("2027-02-02"), LocalDate.parse("2027-02-01"), "ASC", 0, 20),
                "INVALID_DATE_RANGE");
        assertInvalid(parameters(null, null, "ASC", -1, 20), "INVALID_PAGE");
        assertInvalid(parameters(null, null, "ASC", 0, 0), "INVALID_PAGE_SIZE");
        assertInvalid(parameters(null, null, "ASC", 0, 101), "INVALID_PAGE_SIZE");
        assertInvalid(parameters(null, null, "asc", 0, 20), "INVALID_SORT_DIRECTION");
        assertInvalid(parameters(null, null, "", 0, 20), "INVALID_SORT_DIRECTION");
        verify(programRepository, never()).searchVisible(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    void anonymousAndOrdinaryAuthenticatedUsersReceiveOnlyPublicProjection() {
        ProgramEntity announced = announcedProgram(PUBLIC_ID, user, "Public");
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.searchVisible(any(), eq(null), eq(0), eq(20)))
                .thenReturn(new ProgramSearchPage(List.of(announced), 1));
        ProgrammerNameProjection programmer = programmerName(PUBLIC_ID, "Alice Programmer");
        when(roleRepository.findProgrammerNames(List.of(PUBLIC_ID)))
                .thenReturn(List.of(programmer));
        ProgramAuditoriumProjection mainHall = auditorium(PUBLIC_ID, "Main Hall");
        when(screeningRepository.findDistinctScheduledAuditoriums(List.of(PUBLIC_ID)))
                .thenReturn(List.of(mainHall));

        var anonymous = service.searchPrograms(parameters(null, null, null, 0, null));

        assertThat(anonymous.content()).singleElement().isInstanceOfSatisfying(
                PublicProgramResponse.class,
                value -> {
                    assertThat(value.programmerDisplayNames()).containsExactly("Alice Programmer");
                    assertThat(value.finalAuditoriumNames()).containsExactly("Main Hall");
                });
        verify(roleRepository, never()).findAllWithUsersByProgramIds(any());
        verify(screeningRepository, never()).countActiveAndScheduledByProgramIds(any());

        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.searchVisible(any(), eq(USER_ID), eq(0), eq(20)))
                .thenReturn(new ProgramSearchPage(List.of(announced), 1));
        when(roleRepository.findProgramIdsForUserRole(List.of(PUBLIC_ID), USER_ID, ProgramRoleType.PROGRAMMER))
                .thenReturn(List.of());
        var ordinary = service.searchPrograms(parameters(null, null, null, 0, null));
        assertThat(ordinary.content()).singleElement().isInstanceOf(PublicProgramResponse.class);
    }

    @Test
    void programmerReceivesFullDetailsOnlyForManagedProgramsInMixedPage() {
        ProgramEntity managed = program(MANAGED_ID, user, "Managed");
        ProgramEntity announced = announcedProgram(PUBLIC_ID, user, "Public");
        ProgramRoleEntity assignment = new ProgramRoleEntity(
                managed, user, ProgramRoleType.PROGRAMMER, NOW, user);

        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.searchVisible(any(), eq(USER_ID), eq(0), eq(20)))
                .thenReturn(new ProgramSearchPage(List.of(managed, announced), 2));
        when(roleRepository.findProgramIdsForUserRole(
                List.of(MANAGED_ID, PUBLIC_ID), USER_ID, ProgramRoleType.PROGRAMMER))
                .thenReturn(List.of(MANAGED_ID));
        ProgrammerNameProjection managedProgrammer = programmerName(MANAGED_ID, "Alice Programmer");
        ProgrammerNameProjection publicProgrammer = programmerName(PUBLIC_ID, "Public Manager");
        when(roleRepository.findProgrammerNames(List.of(MANAGED_ID, PUBLIC_ID)))
                .thenReturn(List.of(managedProgrammer, publicProgrammer));
        ProgramAuditoriumProjection publicHall = auditorium(PUBLIC_ID, "Public Hall");
        when(screeningRepository.findDistinctScheduledAuditoriums(List.of(MANAGED_ID, PUBLIC_ID)))
                .thenReturn(List.of(publicHall));
        when(roleRepository.findAllWithUsersByProgramIds(List.of(MANAGED_ID)))
                .thenReturn(List.of(assignment));
        ProgramScreeningCountProjection screeningCounts = counts(MANAGED_ID, 3, 1);
        when(screeningRepository.countActiveAndScheduledByProgramIds(List.of(MANAGED_ID)))
                .thenReturn(List.of(screeningCounts));

        var response = service.searchPrograms(parameters(null, null, null, 0, null));

        assertThat(response.content()).hasSize(2);
        assertThat(response.content().get(0)).isInstanceOfSatisfying(FullProgramResponse.class, full -> {
            assertThat(full.state()).isEqualTo(ProgramState.CREATED);
            assertThat(full.creator().username()).isEqualTo("alice");
            assertThat(full.roles()).singleElement().satisfies(role -> {
                assertThat(role.userId()).isEqualTo(USER_ID);
                assertThat(role.role()).isEqualTo(ProgramRoleType.PROGRAMMER);
            });
            assertThat(full.screenings().activeScreeningCount()).isEqualTo(3);
            assertThat(full.screenings().scheduledScreeningCount()).isEqualTo(1);
            assertThat(full.screenings().collectionUrl())
                    .isEqualTo("/api/v1/programs/" + MANAGED_ID + "/screenings");
        });
        assertThat(response.content().get(1)).isInstanceOf(PublicProgramResponse.class);
        verify(roleRepository).findAllWithUsersByProgramIds(List.of(MANAGED_ID));
        verify(screeningRepository).countActiveAndScheduledByProgramIds(List.of(MANAGED_ID));
    }

    @Test
    void projectionFailsClosedIfARepositoryRowIsNeitherAnnouncedNorManagedByRequester() {
        ProgramEntity concealed = program(MANAGED_ID, user, "Concealed");
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.searchVisible(any(), eq(USER_ID), eq(0), eq(20)))
                .thenReturn(new ProgramSearchPage(List.of(concealed), 1));
        when(roleRepository.findProgramIdsForUserRole(
                List.of(MANAGED_ID), USER_ID, ProgramRoleType.PROGRAMMER)).thenReturn(List.of());

        assertThatThrownBy(() -> service.searchPrograms(parameters(null, null, null, 0, null)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining("Program")
                .hasMessageNotContaining(MANAGED_ID.toString());

        verify(roleRepository, never()).findProgrammerNames(any());
        verify(screeningRepository, never()).findDistinctScheduledAuditoriums(any());
        verify(roleRepository, never()).findAllWithUsersByProgramIds(any());
        verify(screeningRepository, never()).countActiveAndScheduledByProgramIds(any());
    }

    @Test
    void directViewReturnsPublicFullOrAccessSafeNotFoundFromVisibleQuery() {
        ProgramEntity publicProgram = announcedProgram(PUBLIC_ID, user, "Public");
        when(currentUser.optional()).thenReturn(Optional.empty());
        when(programRepository.findVisibleById(PUBLIC_ID, null)).thenReturn(Optional.of(publicProgram));
        when(roleRepository.findProgrammerNames(List.of(PUBLIC_ID))).thenReturn(List.of());
        when(screeningRepository.findDistinctScheduledAuditoriums(List.of(PUBLIC_ID))).thenReturn(List.of());
        assertThat(service.viewProgram(PUBLIC_ID)).isInstanceOf(PublicProgramResponse.class);

        ProgramEntity managed = program(MANAGED_ID, user, "Managed");
        when(currentUser.optional()).thenReturn(Optional.of(identity()));
        when(programRepository.findVisibleById(MANAGED_ID, USER_ID)).thenReturn(Optional.of(managed));
        when(roleRepository.findProgramIdsForUserRole(
                List.of(MANAGED_ID), USER_ID, ProgramRoleType.PROGRAMMER)).thenReturn(List.of(MANAGED_ID));
        when(roleRepository.findProgrammerNames(List.of(MANAGED_ID))).thenReturn(List.of());
        when(screeningRepository.findDistinctScheduledAuditoriums(List.of(MANAGED_ID))).thenReturn(List.of());
        when(roleRepository.findAllWithUsersByProgramIds(List.of(MANAGED_ID))).thenReturn(List.of());
        when(screeningRepository.countActiveAndScheduledByProgramIds(List.of(MANAGED_ID))).thenReturn(List.of());
        assertThat(service.viewProgram(MANAGED_ID)).isInstanceOf(FullProgramResponse.class);

        UUID missingOrConcealed = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        when(programRepository.findVisibleById(missingOrConcealed, USER_ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.viewProgram(missingOrConcealed))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageNotContaining("Program")
                .hasMessageNotContaining(missingOrConcealed.toString());
    }

    @Test
    void searchAndViewDeclareReadOnlyTransactions() throws Exception {
        assertThat(SearchAndVisibilityService.class
                .getMethod("searchPrograms", ProgramSearchParameters.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(SearchAndVisibilityService.class
                .getMethod("viewProgram", UUID.class)
                .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private void assertInvalid(ProgramSearchParameters parameters, String errorCode) {
        assertThatThrownBy(() -> service.searchPrograms(parameters))
                .isInstanceOfSatisfying(InvalidInputException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo(errorCode));
    }

    private static ProgramSearchParameters parameters(
            LocalDate fromDate, LocalDate toDate, String direction, int page, Integer size) {
        return new ProgramSearchParameters(
                null, null, fromDate, toDate, null, null, direction, page, size);
    }

    private static ProgramEntity program(UUID id, UserEntity creator, String name) {
        return new ProgramEntity(id, creator, name, "Description",
                LocalDate.parse("2027-01-01"), LocalDate.parse("2027-02-01"), NOW);
    }

    private static ProgramEntity announcedProgram(UUID id, UserEntity creator, String name) {
        ProgramEntity program = program(id, creator, name);
        while (program.getState() != ProgramState.ANNOUNCED) {
            program.transitionTo(program.getState().next().orElseThrow());
        }
        return program;
    }

    private static AuthenticatedUserIdentity identity() {
        return new AuthenticatedUserIdentity(USER_ID, "alice", "Alice Programmer");
    }

    private static ProgrammerNameProjection programmerName(UUID programId, String fullName) {
        ProgrammerNameProjection row = mock(ProgrammerNameProjection.class);
        when(row.getProgramId()).thenReturn(programId);
        when(row.getFullName()).thenReturn(fullName);
        return row;
    }

    private static ProgramAuditoriumProjection auditorium(UUID programId, String name) {
        ProgramAuditoriumProjection row = mock(ProgramAuditoriumProjection.class);
        when(row.getProgramId()).thenReturn(programId);
        when(row.getAuditoriumName()).thenReturn(name);
        return row;
    }

    private static ProgramScreeningCountProjection counts(UUID programId, long active, long scheduled) {
        ProgramScreeningCountProjection row = mock(ProgramScreeningCountProjection.class);
        when(row.getProgramId()).thenReturn(programId);
        when(row.getActiveCount()).thenReturn(active);
        when(row.getScheduledCount()).thenReturn(scheduled);
        return row;
    }
}
