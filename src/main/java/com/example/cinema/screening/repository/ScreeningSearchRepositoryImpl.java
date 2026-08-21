package com.example.cinema.screening.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.screening.api.ScreeningSearchView;
import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

@Repository
public class ScreeningSearchRepositoryImpl implements ScreeningSearchRepository {

    private final EntityManager entityManager;

    public ScreeningSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public ScreeningSearchPage searchVisible(
            UUID programId,
            ScreeningSearchCriteria criteria,
            UUID requesterUserId,
            ProgramRoleType requesterRole,
            int page,
            int size) {
        QueryPlan countPlan = queryPlan(programId, criteria, requesterUserId, requesterRole, true, null);
        long total = apply(entityManager.createQuery(countPlan.jpql(), Long.class), countPlan.parameters())
                .getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return new ScreeningSearchPage(List.of(), total);
        }
        QueryPlan dataPlan = queryPlan(programId, criteria, requesterUserId, requesterRole, false, null);
        TypedQuery<ScreeningEntity> query = apply(
                entityManager.createQuery(dataPlan.jpql(), ScreeningEntity.class), dataPlan.parameters());
        query.setFirstResult(Math.toIntExact(offset));
        query.setMaxResults(size);
        return new ScreeningSearchPage(query.getResultList(), total);
    }

    @Override
    public Optional<ScreeningEntity> findVisibleDetail(
            UUID screeningId,
            UUID requesterUserId,
            ProgramRoleType requesterRole) {
        QueryPlan plan = queryPlan(null, null, requesterUserId, requesterRole, false, screeningId);
        return apply(entityManager.createQuery(plan.jpql(), ScreeningEntity.class), plan.parameters())
                .getResultStream().findFirst();
    }

    static QueryPlan queryPlan(
            UUID programId,
            ScreeningSearchCriteria criteria,
            UUID requesterUserId,
            ProgramRoleType requesterRole,
            boolean count,
            UUID screeningId) {
        StringBuilder jpql = new StringBuilder(count
                ? "select count(s) from ScreeningEntity s where "
                : "select s from ScreeningEntity s join fetch s.program join fetch s.submitter "
                        + "left join fetch s.handler where ");
        Map<String, Object> parameters = new LinkedHashMap<>();
        jpql.append("s.deletedAt is null and ");
        addVisibility(jpql, parameters, requesterUserId, requesterRole);
        if (programId != null) {
            jpql.append(" and s.program.id = :programId");
            parameters.put("programId", programId);
        }
        if (screeningId != null) {
            jpql.append(" and s.id = :screeningId");
            parameters.put("screeningId", screeningId);
        }
        if (criteria != null) {
            addWords(jpql, parameters, "s.filmTitle", "filmTitle", criteria.filmTitleWords());
            addWords(jpql, parameters, "s.castText", "cast", criteria.castWords());
            addWords(jpql, parameters, "s.genre", "genre", criteria.genreWords());
            if (criteria.fromDateTime() != null) {
                jpql.append(" and s.startTime >= :fromDateTime");
                parameters.put("fromDateTime", criteria.fromDateTime());
            }
            if (criteria.toDateTime() != null) {
                jpql.append(" and s.startTime <= :toDateTime");
                parameters.put("toDateTime", criteria.toDateTime());
            }
            if (!count) {
                if (criteria.view() == ScreeningSearchView.TIMETABLE) {
                    jpql.append(" order by s.startTime asc, lower(s.filmTitle) asc, s.id asc");
                } else {
                    jpql.append(" order by lower(s.genre) asc, lower(s.filmTitle) asc, s.id asc");
                }
            }
        }
        return new QueryPlan(jpql.toString(), Map.copyOf(parameters));
    }

    private static void addVisibility(
            StringBuilder jpql,
            Map<String, Object> parameters,
            UUID requesterUserId,
            ProgramRoleType requesterRole) {
        jpql.append("((s.program.state = :announcedState and s.state = :scheduledState)");
        parameters.put("announcedState", ProgramState.ANNOUNCED);
        parameters.put("scheduledState", ScreeningState.SCHEDULED);
        if (requesterUserId != null && requesterRole != null) {
            parameters.put("requesterUserId", requesterUserId);
            parameters.put("requesterRole", requesterRole);
            jpql.append(" or (");
            if (requesterRole == ProgramRoleType.STAFF) {
                jpql.append("s.handler.id = :requesterUserId and ");
            } else if (requesterRole == ProgramRoleType.SUBMITTER) {
                jpql.append("s.submitter.id = :requesterUserId and ");
            }
            jpql.append("exists (select visibilityRole.id.programId from ProgramRoleEntity visibilityRole ")
                    .append("where visibilityRole.id.programId = s.program.id ")
                    .append("and visibilityRole.id.userId = :requesterUserId ")
                    .append("and visibilityRole.role = :requesterRole))");
        }
        jpql.append(')');
    }

    private static void addWords(
            StringBuilder jpql,
            Map<String, Object> parameters,
            String expression,
            String prefix,
            List<String> words) {
        for (int index = 0; index < words.size(); index++) {
            String parameter = prefix + index;
            jpql.append(" and lower(").append(expression).append(") like :")
                    .append(parameter).append(" escape '\\'");
            parameters.put(parameter, containsPattern(words.get(index)));
        }
    }

    static String containsPattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static <T> TypedQuery<T> apply(TypedQuery<T> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
        return query;
    }

    record QueryPlan(String jpql, Map<String, Object> parameters) {
    }
}
