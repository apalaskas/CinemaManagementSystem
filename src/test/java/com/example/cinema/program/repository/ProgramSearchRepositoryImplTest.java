package com.example.cinema.program.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.cinema.program.api.ProgramSortDirection;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.screening.domain.ScreeningState;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

class ProgramSearchRepositoryImplTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @ParameterizedTest
    @MethodSource("individualTextFilters")
    void appliesEveryTextFilterIndependentlyAsCaseInsensitiveContains(
            ProgramSearchCriteria criteria,
            String expression,
            String parameter) {
        var plan = ProgramSearchRepositoryImpl.queryPlan(criteria, null, false, null);

        assertThat(plan.jpql()).contains(expression).contains("like :" + parameter + " escape '\\'");
        assertThat(plan.parameters().get(parameter)).isEqualTo("%mixed case%");
    }

    @Test
    void combinesAllFiltersWithAndSemanticsAndIntervalOverlapBoundaries() {
        var criteria = new ProgramSearchCriteria(
                "festival", "archive", LocalDate.parse("2027-02-01"),
                LocalDate.parse("2027-03-01"), "film", "hall", ProgramSortDirection.ASC);

        var plan = ProgramSearchRepositoryImpl.queryPlan(criteria, USER_ID, false, null);

        assertThat(plan.jpql())
                .contains("and lower(p.name)")
                .contains("and lower(p.description)")
                .contains("and p.startDate <= :toDate")
                .contains("and p.endDate >= :fromDate")
                .contains("and exists (select screening.id")
                .contains("and exists (select auditoriumScreening.id");
        assertThat(plan.parameters())
                .containsEntry("fromDate", LocalDate.parse("2027-02-01"))
                .containsEntry("toDate", LocalDate.parse("2027-03-01"));
    }

    @Test
    void appliesEachOpenEndedDateFilterIndependently() {
        var fromOnly = ProgramSearchRepositoryImpl.queryPlan(new ProgramSearchCriteria(
                null, null, LocalDate.parse("2027-02-01"), null,
                null, null, ProgramSortDirection.ASC), null, false, null);
        var toOnly = ProgramSearchRepositoryImpl.queryPlan(new ProgramSearchCriteria(
                null, null, null, LocalDate.parse("2027-03-01"),
                null, null, ProgramSortDirection.ASC), null, false, null);

        assertThat(fromOnly.jpql()).contains("p.endDate >= :fromDate").doesNotContain(":toDate");
        assertThat(toOnly.jpql()).contains("p.startDate <= :toDate").doesNotContain(":fromDate");
    }

    @Test
    void escapesLiteralPercentUnderscoreAndEscapeCharacters() {
        assertThat(ProgramSearchRepositoryImpl.containsPattern(" 100%_A\\B "))
                .isEqualTo("% 100\\%\\_a\\\\b %");
    }

    @Test
    void usesExistsSubqueriesSoScreeningMatchesCannotDuplicatePrograms() {
        var criteria = new ProgramSearchCriteria(
                null, null, null, null, "film", "hall", ProgramSortDirection.ASC);

        var plan = ProgramSearchRepositoryImpl.queryPlan(criteria, null, false, null);

        assertThat(plan.jpql())
                .doesNotContain("join ScreeningEntity")
                .contains("screening.deletedAt is null")
                .contains("screening.state = :scheduledState")
                .contains("auditoriumScreening.state = :scheduledState")
                .contains("auditoriumScreening.finalAuditoriumName");
        assertThat(plan.parameters()).containsEntry("scheduledState", ScreeningState.SCHEDULED);
    }

    @Test
    void authenticatedFilmFilterMayUsePrivateScreeningsOnlyInsideManagedPrograms() {
        var criteria = new ProgramSearchCriteria(
                null, null, null, null, "film", null, ProgramSortDirection.ASC);

        var plan = ProgramSearchRepositoryImpl.queryPlan(criteria, USER_ID, false, null);

        assertThat(plan.jpql())
                .contains("screening.state = :scheduledState or exists")
                .contains("role.id.programId = p.id")
                .contains("role.id.userId = :requesterUserId")
                .contains("role.role = :programmerRole");
        assertThat(plan.parameters()).containsEntry("programmerRole", ProgramRoleType.PROGRAMMER);
    }

    @Test
    void noFilterSelectsOnlyAnnouncedForAnonymousAndAlsoManagedForAuthenticated() {
        var anonymous = ProgramSearchRepositoryImpl.queryPlan(criteria(ProgramSortDirection.ASC), null, false, null);
        var authenticated = ProgramSearchRepositoryImpl.queryPlan(
                criteria(ProgramSortDirection.ASC), USER_ID, false, null);

        assertThat(anonymous.jpql()).contains("p.state = :announcedState").doesNotContain("requesterUserId");
        assertThat(authenticated.jpql()).contains("p.state = :announcedState or exists");
        assertThat(anonymous.parameters()).containsEntry("announcedState", ProgramState.ANNOUNCED);
    }

    @Test
    void authenticatedVisibilityIsCorrelatedToTheSameProgramAndOnlyProgrammerRole() {
        var plan = ProgramSearchRepositoryImpl.queryPlan(
                criteria(ProgramSortDirection.ASC), USER_ID, false, null);

        assertThat(plan.jpql())
                .contains("role.id.programId = p.id")
                .contains("role.id.userId = :requesterUserId")
                .contains("role.role = :programmerRole")
                .doesNotContain("STAFF", "SUBMITTER");
        assertThat(plan.parameters())
                .containsEntry("requesterUserId", USER_ID)
                .containsEntry("programmerRole", ProgramRoleType.PROGRAMMER);
    }

    @Test
    void appliesRequestedDirectionToAllThreeStableSortKeys() {
        var ascending = ProgramSearchRepositoryImpl.queryPlan(criteria(ProgramSortDirection.ASC), null, false, null);
        var descending = ProgramSearchRepositoryImpl.queryPlan(criteria(ProgramSortDirection.DESC), null, false, null);

        assertThat(ascending.jpql()).endsWith("order by p.startDate asc, lower(p.name) asc, p.id asc");
        assertThat(descending.jpql()).endsWith("order by p.startDate desc, lower(p.name) desc, p.id desc");
    }

    @Test
    void countAndDirectViewQueriesRetainDatabaseVisibilityWithoutFetchOrSortArtifacts() {
        UUID programId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
        var count = ProgramSearchRepositoryImpl.queryPlan(criteria(ProgramSortDirection.ASC), USER_ID, true, null);
        var detail = ProgramSearchRepositoryImpl.queryPlan(null, USER_ID, false, programId);

        assertThat(count.jpql()).startsWith("select count(p)").doesNotContain("fetch", "order by");
        assertThat(detail.jpql()).contains("join fetch p.creator", "p.id = :programId");
        assertThat(detail.parameters()).containsEntry("programId", programId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesCountOffsetLimitAndOrderedSelectionThroughEntityManager() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        TypedQuery<com.example.cinema.program.domain.ProgramEntity> dataQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(entityManager.createQuery(anyString(), eq(com.example.cinema.program.domain.ProgramEntity.class)))
                .thenReturn(dataQuery);
        when(countQuery.getSingleResult()).thenReturn(35L);
        when(dataQuery.getResultList()).thenReturn(List.of());

        var repository = new ProgramSearchRepositoryImpl(entityManager);
        var result = repository.searchVisible(criteria(ProgramSortDirection.DESC), null, 2, 10);

        verify(countQuery).getSingleResult();
        verify(dataQuery).setFirstResult(20);
        verify(dataQuery).setMaxResults(10);
        verify(dataQuery).getResultList();
        assertThat(result.totalElements()).isEqualTo(35);
        assertThat(result.programs()).isEmpty();
    }

    private static ProgramSearchCriteria criteria(ProgramSortDirection direction) {
        return new ProgramSearchCriteria(null, null, null, null, null, null, direction);
    }

    private static Stream<Arguments> individualTextFilters() {
        return Stream.of(
                Arguments.of(new ProgramSearchCriteria(
                        "MiXeD CaSe", null, null, null, null, null, ProgramSortDirection.ASC),
                        "lower(p.name)", "name"),
                Arguments.of(new ProgramSearchCriteria(
                        null, "MiXeD CaSe", null, null, null, null, ProgramSortDirection.ASC),
                        "lower(p.description)", "description"),
                Arguments.of(new ProgramSearchCriteria(
                        null, null, null, null, "MiXeD CaSe", null, ProgramSortDirection.ASC),
                        "lower(screening.filmTitle)", "filmTitle"),
                Arguments.of(new ProgramSearchCriteria(
                        null, null, null, null, null, "MiXeD CaSe", ProgramSortDirection.ASC),
                        "lower(auditoriumScreening.finalAuditoriumName)", "auditorium"));
    }
}
