package com.example.cinema.audit.domain;

import static com.example.cinema.common.domain.DomainAssertions.normalizeOptionalText;
import static com.example.cinema.common.domain.DomainAssertions.requireNonBlank;
import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "audit_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_user_id")
    private UserEntity actor;

    @Column(name = "action_type", nullable = false, length = 100)
    private String actionType;

    @Column(name = "target_entity_type", nullable = false, length = 100)
    private String targetEntityType;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "target_entity_id", nullable = false, columnDefinition = "binary(16)")
    private UUID targetEntityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "json")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "json")
    private String newValue;

    @Column(name = "reason", columnDefinition = "text")
    private String reason;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected AuditLogEntity() {
    }

    public AuditLogEntity(
            UUID id,
            UserEntity actor,
            String actionType,
            String targetEntityType,
            UUID targetEntityId,
            String oldValue,
            String newValue,
            String reason,
            Instant createdAt) {
        this.id = requireNonNull(id, "id");
        this.actor = actor;
        this.actionType = requireNonBlank(actionType, "actionType");
        this.targetEntityType = requireNonBlank(targetEntityType, "targetEntityType");
        this.targetEntityId = requireNonNull(targetEntityId, "targetEntityId");
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.reason = normalizeOptionalText(reason, "reason");
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getActor() {
        return actor;
    }

    public String getActionType() {
        return actionType;
    }

    public String getTargetEntityType() {
        return targetEntityType;
    }

    public UUID getTargetEntityId() {
        return targetEntityId;
    }

    public String getOldValue() {
        return oldValue;
    }

    public String getNewValue() {
        return newValue;
    }

    public String getReason() {
        return reason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
