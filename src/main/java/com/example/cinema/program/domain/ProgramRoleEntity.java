package com.example.cinema.program.domain;

import static java.util.Objects.requireNonNull;

import java.time.Instant;

import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "program_role")
public class ProgramRoleEntity {

    @EmbeddedId
    private ProgramRoleId id;

    @MapsId("programId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private ProgramEntity program;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 32)
    private ProgramRoleType role;

    @Column(name = "assigned_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by_user_id")
    private UserEntity assignedBy;

    protected ProgramRoleEntity() {
    }

    public ProgramRoleEntity(
            ProgramEntity program,
            UserEntity user,
            ProgramRoleType role,
            Instant assignedAt,
            UserEntity assignedBy) {
        this.program = requireNonNull(program, "program");
        this.user = requireNonNull(user, "user");
        this.id = new ProgramRoleId(program.getId(), user.getId());
        this.role = requireNonNull(role, "role");
        this.assignedAt = requireNonNull(assignedAt, "assignedAt");
        this.assignedBy = assignedBy;
    }

    public ProgramRoleId getId() {
        return id;
    }

    public ProgramEntity getProgram() {
        return program;
    }

    public UserEntity getUser() {
        return user;
    }

    public ProgramRoleType getRole() {
        return role;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public UserEntity getAssignedBy() {
        return assignedBy;
    }
}
