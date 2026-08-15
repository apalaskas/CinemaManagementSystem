package com.example.cinema.audit.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.example.cinema.audit.domain.AuditLogEntity;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

    Page<AuditLogEntity> findByTargetEntityTypeAndTargetEntityIdOrderByCreatedAtDesc(
            String targetEntityType,
            UUID targetEntityId,
            Pageable pageable);
}
