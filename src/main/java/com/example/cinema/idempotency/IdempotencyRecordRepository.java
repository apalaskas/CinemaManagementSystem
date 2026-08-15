package com.example.cinema.idempotency;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecordEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r from IdempotencyRecordEntity r
            where r.userId = :userId and r.operation = :operation and r.idempotencyKey = :idempotencyKey
            """)
    Optional<IdempotencyRecordEntity> findForUpdate(
            @Param("userId") UUID userId,
            @Param("operation") String operation,
            @Param("idempotencyKey") String idempotencyKey);

    @Modifying
    @Query("delete from IdempotencyRecordEntity r where r.expiresAt <= :cutoff")
    int deleteExpired(@Param("cutoff") Instant cutoff);
}
