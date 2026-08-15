package com.example.cinema.audit.service;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.cinema.audit.domain.AuditLogEntity;
import com.example.cinema.audit.repository.AuditLogRepository;
import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.EntityManager;

@Service
public class AuditLoggingService {

    private final AuditLogRepository repository;
    private final AuditSnapshotSanitizer sanitizer;
    private final EntityManager entityManager;
    private final Clock clock;

    public AuditLoggingService(
            AuditLogRepository repository,
            AuditSnapshotSanitizer sanitizer,
            EntityManager entityManager,
            Clock clock) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLogEntity recordUserAction(
            UUID actorUserId,
            String actionType,
            String targetEntityType,
            UUID targetEntityId,
            Map<String, ?> oldSnapshot,
            Map<String, ?> newSnapshot,
            String reason) {
        requireNonNull(actorUserId, "actorUserId");
        UserEntity actor = entityManager.getReference(UserEntity.class, actorUserId);
        return save(actor, actionType, targetEntityType, targetEntityId, oldSnapshot, newSnapshot, reason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditLogEntity recordSystemAction(
            String actionType,
            String targetEntityType,
            UUID targetEntityId,
            Map<String, ?> oldSnapshot,
            Map<String, ?> newSnapshot,
            String reason) {
        return save(null, actionType, targetEntityType, targetEntityId, oldSnapshot, newSnapshot, reason);
    }

    private AuditLogEntity save(
            UserEntity actor,
            String actionType,
            String targetEntityType,
            UUID targetEntityId,
            Map<String, ?> oldSnapshot,
            Map<String, ?> newSnapshot,
            String reason) {
        Instant timestamp = clock.instant();
        AuditLogEntity entry = new AuditLogEntity(
                UUID.randomUUID(), actor, actionType, targetEntityType, targetEntityId,
                sanitizer.safeJson(oldSnapshot), sanitizer.safeJson(newSnapshot), reason, timestamp);
        return repository.save(entry);
    }
}
