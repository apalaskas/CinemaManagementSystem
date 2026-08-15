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
}
