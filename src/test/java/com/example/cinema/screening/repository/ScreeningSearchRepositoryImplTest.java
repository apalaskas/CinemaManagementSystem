package com.example.cinema.screening.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.screening.api.ScreeningSearchView;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

class ScreeningSearchRepositoryImplTest {

    private static final UUID PROGRAM_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final Instant FROM = Instant.parse("2027-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T20:00:00Z");

    @ParameterizedTest
    @MethodSource("individualTextFilters")
    void appliesEachTextFilterIndependentlyAsCaseInsensitiveAllWordContains(
            ScreeningSearchCriteria criteria, String expression, String parameter) {
        var plan = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria, null, null, false, null);

        assertThat(plan.jpql()).contains("and lower(" + expression + ") like :" + parameter);
        assertThat(plan.parameters()).containsEntry(parameter, "%mixed%");
    }

    @Test
    void combinesEveryFilterWithAndAndEveryWordWithinItsOwnField() {
        ScreeningSearchCriteria criteria = new ScreeningSearchCriteria(
                List.of("dark", "night"), List.of("alice", "bob"), List.of("science", "fiction"),
                FROM, TO, ScreeningSearchView.GENERAL);

        var plan = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria, null, null, false, null);

        assertThat(plan.jpql())
                .contains("and lower(s.filmTitle) like :filmTitle0")
                .contains("and lower(s.filmTitle) like :filmTitle1")
                .contains("and lower(s.castText) like :cast0")
                .contains("and lower(s.castText) like :cast1")
                .contains("and lower(s.genre) like :genre0")
                .contains("and lower(s.genre) like :genre1")
                .contains("and s.startTime >= :fromDateTime")
                .contains("and s.startTime <= :toDateTime");
        assertThat(plan.parameters())
                .containsEntry("filmTitle0", "%dark%")
                .containsEntry("filmTitle1", "%night%")
                .containsEntry("fromDateTime", FROM)
                .containsEntry("toDateTime", TO);
    }

    @Test
    void appliesEachInclusiveDateBoundaryIndependentlyAndNoFilterAddsNoPredicate() {
        var fromOnly = ScreeningSearchRepositoryImpl.queryPlan(PROGRAM_ID,
                new ScreeningSearchCriteria(List.of(), List.of(), List.of(), FROM, null,
                        ScreeningSearchView.GENERAL), null, null, false, null);
        var toOnly = ScreeningSearchRepositoryImpl.queryPlan(PROGRAM_ID,
                new ScreeningSearchCriteria(List.of(), List.of(), List.of(), null, TO,
                        ScreeningSearchView.GENERAL), null, null, false, null);
        var noFilter = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL), null, null, false, null);

        assertThat(fromOnly.jpql()).contains("s.startTime >= :fromDateTime").doesNotContain(":toDateTime");
        assertThat(toOnly.jpql()).contains("s.startTime <= :toDateTime").doesNotContain(":fromDateTime");
        assertThat(noFilter.jpql()).doesNotContain(" like :", ":fromDateTime", ":toDateTime");
    }

    @Test
    void treatsPercentUnderscoreAndEscapeCharactersLiterally() {
        assertThat(ScreeningSearchRepositoryImpl.containsPattern("100%_A\\B"))
                .isEqualTo("%100\\%\\_a\\\\b%");
    }

    @Test
    void anonymousAndOrdinaryVisibilityIsOnlyAnnouncedScheduledAndActive() {
        var anonymous = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL), null, null, false, null);
        var ordinary = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL), USER_ID, null, true, null);

        assertThat(anonymous.jpql())
                .contains("s.deletedAt is null")
                .contains("s.program.state = :announcedState")
                .contains("s.state = :scheduledState")
                .doesNotContain("handler.id", "submitter.id");
        assertThat(ordinary.jpql()).doesNotContain("requesterUserId");
        assertThat(anonymous.parameters())
                .containsEntry("announcedState", ProgramState.ANNOUNCED)
                .containsEntry("scheduledState", ScreeningState.SCHEDULED);
    }

    @Test
    void programmerVisibilityIsRevalidatedByASameProgramDatabaseRolePredicate() {
        var plan = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL),
                USER_ID, ProgramRoleType.PROGRAMMER, false, null);

        assertThat(plan.jpql())
                .contains("s.deletedAt is null")
                .contains("visibilityRole.id.programId = s.program.id")
                .contains("visibilityRole.id.userId = :requesterUserId")
                .contains("visibilityRole.role = :requesterRole")
                .doesNotContain("s.handler.id = :requesterUserId", "s.submitter.id = :requesterUserId");
        assertThat(plan.parameters())
                .containsEntry("programId", PROGRAM_ID)
                .containsEntry("requesterUserId", USER_ID)
                .containsEntry("requesterRole", ProgramRoleType.PROGRAMMER);
    }

    @Test
    void staffAndSubmitterVisibilityMixOwnedFullCandidatesWithPublicRows() {
        var staff = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL),
                USER_ID, ProgramRoleType.STAFF, false, null);
        var submitter = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL),
                USER_ID, ProgramRoleType.SUBMITTER, false, null);

        assertThat(staff.jpql())
                .contains("s.program.state = :announcedState and s.state = :scheduledState")
                .contains("s.handler.id = :requesterUserId")
                .contains("visibilityRole.id.programId = s.program.id")
                .doesNotContain("submitter.id = :requesterUserId");
        assertThat(submitter.jpql())
                .contains("s.submitter.id = :requesterUserId")
                .contains("visibilityRole.id.programId = s.program.id")
                .doesNotContain("handler.id = :requesterUserId");
        assertThat(staff.parameters()).containsEntry("requesterRole", ProgramRoleType.STAFF);
        assertThat(submitter.parameters()).containsEntry("requesterRole", ProgramRoleType.SUBMITTER);
    }

    @Test
    void generalAndTimetableUseDocumentedStableDatabaseOrdering() {
        var general = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL), null, null, false, null);
        var timetable = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.TIMETABLE), null, null, false, null);

        assertThat(general.jpql()).endsWith(
                "order by lower(s.genre) asc, lower(s.filmTitle) asc, s.id asc");
        assertThat(timetable.jpql()).endsWith(
                "order by s.startTime asc, lower(s.filmTitle) asc, s.id asc");
    }

    @Test
    void countAndDataQueriesShareFiltersAndVisibilityWhileOnlyDataSorts() {
        var count = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.TIMETABLE),
                USER_ID, ProgramRoleType.STAFF, true, null);
        var data = ScreeningSearchRepositoryImpl.queryPlan(
                PROGRAM_ID, criteria(ScreeningSearchView.TIMETABLE),
                USER_ID, ProgramRoleType.STAFF, false, null);

        assertThat(count.jpql()).startsWith("select count(s)").doesNotContain("fetch", "order by");
        assertThat(data.jpql()).contains("join fetch s.program", "join fetch s.submitter")
                .contains("left join fetch s.handler", "order by");
        assertThat(count.parameters()).isEqualTo(data.parameters());
        assertThat(predicateOf(count.jpql())).isEqualTo(predicateOf(data.jpql()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void executesCountOrderingOffsetAndLimitInTheRepository() {
        EntityManager entityManager = mock(EntityManager.class);
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        TypedQuery<ScreeningEntity> dataQuery = mock(TypedQuery.class);
        when(entityManager.createQuery(anyString(), eq(Long.class))).thenReturn(countQuery);
        when(entityManager.createQuery(anyString(), eq(ScreeningEntity.class))).thenReturn(dataQuery);
        when(countQuery.getSingleResult()).thenReturn(35L);
        when(dataQuery.getResultList()).thenReturn(List.of());

        ScreeningSearchRepositoryImpl repository = new ScreeningSearchRepositoryImpl(entityManager);
        ScreeningSearchPage page = repository.searchVisible(
                PROGRAM_ID, criteria(ScreeningSearchView.GENERAL), null, null, 2, 10);

        verify(countQuery).getSingleResult();
        verify(dataQuery).setFirstResult(20);
        verify(dataQuery).setMaxResults(10);
        verify(dataQuery).getResultList();
        assertThat(page.totalElements()).isEqualTo(35);
    }

    @Test
    void directViewRetainsVisibilityAndActivePredicatesWithoutPaginationArtifacts() {
        UUID screeningId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
        var plan = ScreeningSearchRepositoryImpl.queryPlan(
                null, null, USER_ID, ProgramRoleType.SUBMITTER, false, screeningId);

        assertThat(plan.jpql())
                .contains("s.deletedAt is null")
                .contains("s.submitter.id = :requesterUserId")
                .contains("visibilityRole.id.programId = s.program.id")
                .contains("s.id = :screeningId")
                .doesNotContain("order by");
        assertThat(plan.parameters()).containsEntry("screeningId", screeningId);
    }

    private static ScreeningSearchCriteria criteria(ScreeningSearchView view) {
        return new ScreeningSearchCriteria(List.of(), List.of(), List.of(), null, null, view);
    }

    private static String predicateOf(String jpql) {
        String predicate = jpql.substring(jpql.indexOf(" where ") + " where ".length());
        int orderIndex = predicate.indexOf(" order by ");
        return orderIndex < 0 ? predicate : predicate.substring(0, orderIndex);
    }

    private static Stream<Arguments> individualTextFilters() {
        return Stream.of(
                Arguments.of(new ScreeningSearchCriteria(
                        List.of("MiXeD"), List.of(), List.of(), null, null, ScreeningSearchView.GENERAL),
                        "s.filmTitle", "filmTitle0"),
                Arguments.of(new ScreeningSearchCriteria(
                        List.of(), List.of("MiXeD"), List.of(), null, null, ScreeningSearchView.GENERAL),
                        "s.castText", "cast0"),
                Arguments.of(new ScreeningSearchCriteria(
                        List.of(), List.of(), List.of("MiXeD"), null, null, ScreeningSearchView.GENERAL),
                        "s.genre", "genre0"));
    }
}
