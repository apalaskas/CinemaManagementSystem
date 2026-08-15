package com.example.cinema.program.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class ProgramRoleId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "program_id", nullable = false, columnDefinition = "binary(16)")
    private UUID programId;

    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "user_id", nullable = false, columnDefinition = "binary(16)")
    private UUID userId;

    protected ProgramRoleId() {
    }

    public ProgramRoleId(UUID programId, UUID userId) {
        this.programId = Objects.requireNonNull(programId, "programId");
        this.userId = Objects.requireNonNull(userId, "userId");
    }

    public UUID getProgramId() {
        return programId;
    }

    public UUID getUserId() {
        return userId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProgramRoleId that)) {
            return false;
        }
        return programId.equals(that.programId) && userId.equals(that.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(programId, userId);
    }
}
