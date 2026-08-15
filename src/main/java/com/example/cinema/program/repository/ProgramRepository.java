package com.example.cinema.program.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.cinema.program.domain.ProgramEntity;

import jakarta.persistence.LockModeType;

public interface ProgramRepository extends JpaRepository<ProgramEntity, UUID> {

    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProgramEntity p where p.id = :programId")
    Optional<ProgramEntity> findByIdForUpdate(@Param("programId") UUID programId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ProgramEntity p set p.version = p.version + 1 where p.id = :programId and p.version = :expectedVersion")
    int incrementVersion(
            @Param("programId") UUID programId,
            @Param("expectedVersion") long expectedVersion);
}
