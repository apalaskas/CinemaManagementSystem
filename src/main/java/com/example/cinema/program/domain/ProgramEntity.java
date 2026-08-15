package com.example.cinema.program.domain;

import static com.example.cinema.common.domain.DomainAssertions.requireNonBlank;
import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.cinema.user.domain.UserEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "program")
public class ProgramEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "program_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_user_id", nullable = false)
    private UserEntity creator;

    @Column(name = "name", nullable = false, unique = true, length = 255)
    private String name;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ProgramState state;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProgramEntity() {
    }

    public ProgramEntity(
            UUID id,
            UserEntity creator,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            Instant createdAt) {
        this.id = requireNonNull(id, "id");
        this.creator = requireNonNull(creator, "creator");
        this.name = requireNonBlank(name, "name");
        this.description = requireNonBlank(description, "description");
        this.startDate = requireNonNull(startDate, "startDate");
        this.endDate = requireNonNull(endDate, "endDate");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        this.createdAt = requireNonNull(createdAt, "createdAt");
        this.state = ProgramState.CREATED;
    }

    public UUID getId() {
        return id;
    }

    public UserEntity getCreator() {
        return creator;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public ProgramState getState() {
        return state;
    }

    public long getVersion() {
        return version;
    }
}
