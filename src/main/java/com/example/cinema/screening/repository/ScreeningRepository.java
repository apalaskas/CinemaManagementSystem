package com.example.cinema.screening.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.cinema.screening.domain.ScreeningEntity;
import com.example.cinema.screening.domain.ScreeningState;

import jakarta.persistence.LockModeType;

public interface ScreeningRepository extends JpaRepository<ScreeningEntity, UUID> {

    @Query("select s from ScreeningEntity s where s.id = :screeningId and s.deletedAt is null")
    Optional<ScreeningEntity> findActiveById(@Param("screeningId") UUID screeningId);

    @Query("select s.program.id from ScreeningEntity s where s.id = :screeningId and s.deletedAt is null")
    Optional<UUID> findActiveProgramIdById(@Param("screeningId") UUID screeningId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from ScreeningEntity s where s.id = :screeningId and s.deletedAt is null")
    Optional<ScreeningEntity> findActiveByIdForUpdate(@Param("screeningId") UUID screeningId);

    @Query("select s from ScreeningEntity s where s.program.id = :programId and s.deletedAt is null")
    Page<ScreeningEntity> findActiveByProgramId(
            @Param("programId") UUID programId,
            Pageable pageable);

    @Query("""
            select s from ScreeningEntity s
            where s.program.id = :programId
              and s.submitter.id = :submitterUserId
              and s.deletedAt is null
            """)
    Page<ScreeningEntity> findActiveOwnedBy(
            @Param("programId") UUID programId,
            @Param("submitterUserId") UUID submitterUserId,
            Pageable pageable);

    @Query("""
            select s from ScreeningEntity s
            where s.program.id = :programId
              and s.handler.id = :handlerUserId
              and s.deletedAt is null
            """)
    Page<ScreeningEntity> findActiveAssignedTo(
            @Param("programId") UUID programId,
            @Param("handlerUserId") UUID handlerUserId,
            Pageable pageable);

    @Query("""
            select count(s)
            from ScreeningEntity s
            where s.program.id = :programId
              and s.deletedAt is null
              and s.state = :state
            """)
    long countActiveByProgramIdAndState(
            @Param("programId") UUID programId,
            @Param("state") ScreeningState state);

    @Query("""
            select count(s)
            from ScreeningEntity s
            where s.program.id = :programId
              and s.deletedAt is null
              and s.state = com.example.cinema.screening.domain.ScreeningState.SUBMITTED
              and (
                  s.handler is null
                  or not exists (
                      select r.id.programId
                      from ProgramRoleEntity r
                      where r.id.programId = :programId
                        and r.id.userId = s.handler.id
                        and r.role = com.example.cinema.program.domain.ProgramRoleType.STAFF
                  )
              )
            """)
    long countActiveSubmittedWithoutFrozenStaffHandler(@Param("programId") UUID programId);

    @Query("""
            select count(s)
            from ScreeningEntity s
            where s.program.id = :programId
              and s.deletedAt is null
              and s.state <> com.example.cinema.screening.domain.ScreeningState.CREATED
              and (
                  s.state <> com.example.cinema.screening.domain.ScreeningState.REVIEWED
                  or not exists (
                      select review.id
                      from ReviewEntity review
                      where review.screening.id = s.id
                  )
              )
            """)
    long countActiveReviewCompletionViolations(@Param("programId") UUID programId);

    @Query("""
            select count(s)
            from ScreeningEntity s
            where s.program.id = :programId
              and s.deletedAt is null
              and s.state not in (
                  com.example.cinema.screening.domain.ScreeningState.CREATED,
                  com.example.cinema.screening.domain.ScreeningState.APPROVED,
                  com.example.cinema.screening.domain.ScreeningState.REJECTED
              )
            """)
    long countActiveDecisionPreparationViolations(@Param("programId") UUID programId);

    @Query("""
            select count(s)
            from ScreeningEntity s
            where s.program.id = :programId
              and s.deletedAt is null
              and s.state in (
                  com.example.cinema.screening.domain.ScreeningState.SUBMITTED,
                  com.example.cinema.screening.domain.ScreeningState.REVIEWED,
                  com.example.cinema.screening.domain.ScreeningState.APPROVED
              )
            """)
    long countActiveNonFinalDecisionWorkflow(@Param("programId") UUID programId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ScreeningEntity s
            where s.program.id = :programId
              and s.state = com.example.cinema.screening.domain.ScreeningState.APPROVED
              and s.finalSubmittedAt is null
              and s.deletedAt is null
            order by s.id
            """)
    List<ScreeningEntity> findApprovedWithoutFinalSubmissionForUpdate(
            @Param("programId") UUID programId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s from ScreeningEntity s
            where s.state = com.example.cinema.screening.domain.ScreeningState.SCHEDULED
              and s.deletedAt is null
              and s.finalAuditoriumName = :finalAuditoriumName
              and s.id <> :excludedScreeningId
              and s.startTime < :requestedEnd
              and s.endTime > :requestedStart
            order by s.startTime, s.id
            """)
    List<ScreeningEntity> findSchedulingConflictsForUpdate(
            @Param("excludedScreeningId") UUID excludedScreeningId,
            @Param("finalAuditoriumName") String finalAuditoriumName,
            @Param("requestedStart") Instant requestedStart,
            @Param("requestedEnd") Instant requestedEnd);

    @Query("""
            select distinct s.program.id as programId, s.finalAuditoriumName as auditoriumName
            from ScreeningEntity s
            where s.program.id in :programIds
              and s.deletedAt is null
              and s.state = com.example.cinema.screening.domain.ScreeningState.SCHEDULED
              and s.finalAuditoriumName is not null
            order by s.program.id, s.finalAuditoriumName
            """)
    List<ProgramAuditoriumProjection> findDistinctScheduledAuditoriums(
            @Param("programIds") List<UUID> programIds);

    @Query("""
            select s.program.id as programId,
                   count(s) as activeCount,
                   sum(case when s.state = com.example.cinema.screening.domain.ScreeningState.SCHEDULED
                            then 1 else 0 end) as scheduledCount
            from ScreeningEntity s
            where s.program.id in :programIds
              and s.deletedAt is null
            group by s.program.id
            """)
    List<ProgramScreeningCountProjection> countActiveAndScheduledByProgramIds(
            @Param("programIds") List<UUID> programIds);
}
