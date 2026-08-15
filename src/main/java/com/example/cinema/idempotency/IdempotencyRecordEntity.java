package com.example.cinema.idempotency;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(name = "idempotency_record", uniqueConstraints = @UniqueConstraint(
        name = "uk_idempotency_user_key",
        columnNames = {"user_id", "idempotency_key"}))
public class IdempotencyRecordEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "idempotency_record_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, columnDefinition = "binary(16)")
    private UUID userId;

    @Column(name = "operation", nullable = false, length = 100)
    private String operation;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "request_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body", columnDefinition = "longtext")
    private String responseBody;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant expiresAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(
            UUID id,
            UUID userId,
            String operation,
            String idempotencyKey,
            byte[] requestHash,
            Instant createdAt,
            Instant expiresAt) {
        this.id = requireNonNull(id, "id");
        this.userId = requireNonNull(userId, "userId");
        this.operation = requireNonNull(operation, "operation");
        this.idempotencyKey = requireNonNull(idempotencyKey, "idempotencyKey");
        this.requestHash = requireNonNull(requestHash, "requestHash").clone();
        this.createdAt = requireNonNull(createdAt, "createdAt");
        this.expiresAt = requireNonNull(expiresAt, "expiresAt");
        this.status = IdempotencyStatus.IN_PROGRESS;
    }

    public void complete(int responseStatus, String responseBody) {
        if (responseStatus < 200 || responseStatus > 299) {
            throw new IllegalArgumentException("Only successful responses can be stored");
        }
        this.responseStatus = responseStatus;
        this.responseBody = requireNonNull(responseBody, "responseBody");
        this.status = IdempotencyStatus.COMPLETED;
    }

    public boolean hasRequestHash(byte[] candidate) {
        return Arrays.equals(requestHash, candidate);
    }

    public boolean belongsToOperation(String candidate) {
        return operation.equals(candidate);
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getOperation() { return operation; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public byte[] getRequestHash() { return requestHash.clone(); }
    public IdempotencyStatus getStatus() { return status; }
    public Integer getResponseStatus() { return responseStatus; }
    public String getResponseBody() { return responseBody; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
}
