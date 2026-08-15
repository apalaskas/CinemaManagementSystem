package com.example.cinema.program.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.cinema.program.domain.ProgramRoleEntity;
import com.example.cinema.program.domain.ProgramRoleId;
import com.example.cinema.program.domain.ProgramRoleType;

public interface ProgramRoleRepository extends JpaRepository<ProgramRoleEntity, ProgramRoleId> {

    @Query("select r from ProgramRoleEntity r where r.id.programId = :programId and r.id.userId = :userId")
    Optional<ProgramRoleEntity> findRole(
            @Param("programId") UUID programId,
            @Param("userId") UUID userId);

    @Query("select r from ProgramRoleEntity r where r.id.programId = :programId order by r.assignedAt, r.id.userId")
    List<ProgramRoleEntity> findAllByProgramId(@Param("programId") UUID programId);

    @Query("select count(r) > 0 from ProgramRoleEntity r where r.id.programId = :programId and r.role = :role")
    boolean existsByProgramIdAndRole(
            @Param("programId") UUID programId,
            @Param("role") ProgramRoleType role);

    long countByIdProgramId(UUID programId);

    @Query("""
            select r.id.programId
            from ProgramRoleEntity r
            where r.id.programId in :programIds
              and r.id.userId = :userId
              and r.role = :role
            """)
    List<UUID> findProgramIdsForUserRole(
            @Param("programIds") List<UUID> programIds,
            @Param("userId") UUID userId,
            @Param("role") ProgramRoleType role);

    @Query("""
            select r.id.programId as programId, r.user.fullName as fullName
            from ProgramRoleEntity r
            where r.id.programId in :programIds
              and r.role = com.example.cinema.program.domain.ProgramRoleType.PROGRAMMER
            order by r.id.programId, lower(r.user.fullName), r.id.userId
            """)
    List<ProgrammerNameProjection> findProgrammerNames(
            @Param("programIds") List<UUID> programIds);

    @Query("""
            select r from ProgramRoleEntity r
            join fetch r.user
            left join fetch r.assignedBy
            where r.id.programId in :programIds
            order by r.id.programId, r.assignedAt, r.id.userId
            """)
    List<ProgramRoleEntity> findAllWithUsersByProgramIds(
            @Param("programIds") List<UUID> programIds);
}
