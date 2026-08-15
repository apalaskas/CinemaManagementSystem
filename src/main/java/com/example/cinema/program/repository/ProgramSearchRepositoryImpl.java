package com.example.cinema.program.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.example.cinema.program.api.ProgramSortDirection;
import com.example.cinema.program.domain.ProgramEntity;
import com.example.cinema.program.domain.ProgramRoleType;
import com.example.cinema.program.domain.ProgramState;
import com.example.cinema.screening.domain.ScreeningState;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

@Repository
public class ProgramSearchRepositoryImpl implements ProgramSearchRepository {

    private static final String PROGRAMMER_VISIBILITY = """
            exists (select role.id.programId from ProgramRoleEntity role
                    where role.id.programId = p.id
                      and role.id.userId = :requesterUserId
                      and role.role = :programmerRole)
            """;

    private final EntityManager entityManager;

    public ProgramSearchRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public ProgramSearchPage searchVisible(
            ProgramSearchCriteria criteria,
            UUID requesterUserId,
            int page,
            int size) {
        QueryPlan countPlan = queryPlan(criteria, requesterUserId, true, null);
        long total = apply(entityManager.createQuery(countPlan.jpql(), Long.class), countPlan.parameters())
                .getSingleResult();
        long offset = (long) page * size;
        if (offset >= total) {
            return new ProgramSearchPage(List.of(), total);
        }

        QueryPlan dataPlan = queryPlan(criteria, requesterUserId, false, null);
        TypedQuery<ProgramEntity> query = apply(
                entityManager.createQuery(dataPlan.jpql(), ProgramEntity.class), dataPlan.parameters());
        query.setFirstResult(Math.toIntExact(offset));
        query.setMaxResults(size);
        return new ProgramSearchPage(query.getResultList(), total);
    }

    @Override
    public Optional<ProgramEntity> findVisibleById(UUID programId, UUID requesterUserId) {
        QueryPlan plan = queryPlan(null, requesterUserId, false, programId);
        return apply(entityManager.createQuery(plan.jpql(), ProgramEntity.class), plan.parameters())
                .getResultStream()
                .findFirst();
    }

    static QueryPlan queryPlan(
            ProgramSearchCriteria criteria,
            UUID requesterUserId,
            boolean count,
            UUID programId) {
        StringBuilder jpql = new StringBuilder(count
                ? "select count(p) from ProgramEntity p where "
                : "select p from ProgramEntity p join fetch p.creator where ");
        Map<String, Object> parameters = new LinkedHashMap<>();

        jpql.append("(p.state = :announcedState");
        parameters.put("announcedState", ProgramState.ANNOUNCED);
        if (requesterUserId != null) {
            jpql.append(" or ").append(PROGRAMMER_VISIBILITY);
            addRequesterParameters(parameters, requesterUserId);
        }
        jpql.append(')');

        if (programId != null) {
            jpql.append(" and p.id = :programId");
            parameters.put("programId", programId);
        }
        if (criteria != null) {
            addFilters(jpql, parameters, criteria, requesterUserId != null);
            if (!count) {
                String order = criteria.direction() == ProgramSortDirection.DESC ? " desc" : " asc";
                jpql.append(" order by p.startDate").append(order)
                        .append(", lower(p.name)").append(order)
                        .append(", p.id").append(order);
            }
        }
        return new QueryPlan(jpql.toString(), Map.copyOf(parameters));
    }

    private static void addFilters(
            StringBuilder jpql,
            Map<String, Object> parameters,
            ProgramSearchCriteria criteria,
            boolean authenticated) {
        addTextFilter(jpql, parameters, "p.name", "name", criteria.name());
        addTextFilter(jpql, parameters, "p.description", "description", criteria.description());
        if (criteria.toDate() != null) {
            jpql.append(" and p.startDate <= :toDate");
            parameters.put("toDate", criteria.toDate());
        }
        if (criteria.fromDate() != null) {
            jpql.append(" and p.endDate >= :fromDate");
            parameters.put("fromDate", criteria.fromDate());
        }
        if (criteria.filmTitle() != null) {
            jpql.append("""
                     and exists (select screening.id from ScreeningEntity screening
                                 where screening.program.id = p.id
                                   and screening.deletedAt is null
                                   and lower(screening.filmTitle) like :filmTitle escape '\\'
                    """);
            parameters.put("filmTitle", containsPattern(criteria.filmTitle()));
            if (authenticated) {
                jpql.append(" and (screening.state = :scheduledState or ")
                        .append(PROGRAMMER_VISIBILITY)
                        .append(')');
            } else {
                jpql.append(" and screening.state = :scheduledState");
            }
            jpql.append(')');
            parameters.put("scheduledState", ScreeningState.SCHEDULED);
        }
        if (criteria.auditorium() != null) {
            jpql.append("""
                     and exists (select auditoriumScreening.id from ScreeningEntity auditoriumScreening
                                 where auditoriumScreening.program.id = p.id
                                   and auditoriumScreening.deletedAt is null
                                   and auditoriumScreening.state = :scheduledState
                                   and lower(auditoriumScreening.finalAuditoriumName) like :auditorium escape '\\')
                    """);
            parameters.put("auditorium", containsPattern(criteria.auditorium()));
            parameters.put("scheduledState", ScreeningState.SCHEDULED);
        }
    }

    private static void addTextFilter(
            StringBuilder jpql,
            Map<String, Object> parameters,
            String expression,
            String parameter,
            String value) {
        if (value != null) {
            jpql.append(" and lower(").append(expression).append(") like :")
                    .append(parameter).append(" escape '\\'");
            parameters.put(parameter, containsPattern(value));
        }
    }

    static String containsPattern(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return "%" + normalized
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_") + "%";
    }

    private static void addRequesterParameters(Map<String, Object> parameters, UUID requesterUserId) {
        parameters.put("requesterUserId", requesterUserId);
        parameters.put("programmerRole", ProgramRoleType.PROGRAMMER);
    }

    private static <T> TypedQuery<T> apply(TypedQuery<T> query, Map<String, Object> parameters) {
        parameters.forEach(query::setParameter);
        return query;
    }

    record QueryPlan(String jpql, Map<String, Object> parameters) {
    }
}
