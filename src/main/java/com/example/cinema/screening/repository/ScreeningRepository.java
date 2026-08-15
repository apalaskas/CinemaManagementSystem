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

import jakarta.persistence.LockModeType;

public interface ScreeningRepository extends JpaRepository<ScreeningEntity, UUID> {

    @Query("select s from ScreeningEntity s where s.id = :screeningId and s.deletedAt is null")
    Optional<ScreeningEntity> findActiveById(@Param("screeningId") UUID screeningId);

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
              and lower(s.finalAuditoriumName) = lower(:finalAuditoriumName)
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
}
