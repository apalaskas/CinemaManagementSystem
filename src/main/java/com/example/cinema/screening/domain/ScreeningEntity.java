package com.example.cinema.screening.domain;

import static com.example.cinema.common.domain.DomainAssertions.normalizeOptionalText;
import static com.example.cinema.common.domain.DomainAssertions.requireNonBlank;
import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.example.cinema.program.domain.ProgramEntity;
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
@Table(name = "screening")
public class ScreeningEntity {

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "screening_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "program_id", nullable = false)
    private ProgramEntity program;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "submitter_user_id", nullable = false)
    private UserEntity submitter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handler_user_id")
    private UserEntity handler;

    @Column(name = "film_title", length = 255)
    private String filmTitle;

    @Column(name = "cast_text", columnDefinition = "text")
    private String castText;

    @Column(name = "genre", length = 255)
    private String genre;

    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    @Column(name = "candidate_auditorium_name", length = 255)
    private String candidateAuditoriumName;

    @Column(name = "final_auditorium_name", length = 255)
    private String finalAuditoriumName;

    @Column(name = "start_time", columnDefinition = "datetime(6)")
    private Instant startTime;

    @Column(name = "end_time", columnDefinition = "datetime(6)")
    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private ScreeningState state;

    @Column(name = "conditional_notes", columnDefinition = "text")
    private String conditionalNotes;

    @Column(name = "final_submitted_at", columnDefinition = "datetime(6)")
    private Instant finalSubmittedAt;

    @Column(name = "rejection_reason", columnDefinition = "text")
    private String rejectionReason;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    @Column(name = "deleted_at", columnDefinition = "datetime(6)")
    private Instant deletedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ScreeningEntity() {
    }

    public ScreeningEntity(
            UUID id,
            ProgramEntity program,
            UserEntity submitter,
            String filmTitle,
            String castText,
            String genre,
            Integer durationMinutes,
            String candidateAuditoriumName,
            Instant startTime,
            Instant endTime,
            Instant createdAt) {
        this.id = requireNonNull(id, "id");
        this.program = requireNonNull(program, "program");
        this.submitter = requireNonNull(submitter, "submitter");
        this.filmTitle = normalizeOptionalText(filmTitle, "filmTitle");
        this.castText = normalizeOptionalText(castText, "castText");
        this.genre = normalizeOptionalText(genre, "genre");
        this.durationMinutes = validateDuration(durationMinutes);
        this.candidateAuditoriumName = normalizeOptionalText(
                candidateAuditoriumName, "candidateAuditoriumName");
        validateInterval(startTime, endTime, durationMinutes);
        this.startTime = startTime;
        this.endTime = endTime;
        this.createdAt = requireNonNull(createdAt, "createdAt");
        this.state = ScreeningState.CREATED;
    }

    private static Integer validateDuration(Integer durationMinutes) {
        if (durationMinutes != null && durationMinutes <= 0) {
            throw new IllegalArgumentException("durationMinutes must be positive when supplied");
        }
        return durationMinutes;
    }

    private static void validateInterval(Instant startTime, Instant endTime, Integer durationMinutes) {
        if (startTime != null && endTime != null) {
            if (!endTime.isAfter(startTime)) {
                throw new IllegalArgumentException("endTime must be after startTime");
            }
            if (durationMinutes != null
                    && Duration.between(startTime, endTime).compareTo(Duration.ofMinutes(durationMinutes)) < 0) {
                throw new IllegalArgumentException("screening interval must be at least durationMinutes");
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public ProgramEntity getProgram() {
        return program;
    }

    public UserEntity getSubmitter() {
        return submitter;
    }

    public UserEntity getHandler() {
        return handler;
    }

    public String getFilmTitle() {
        return filmTitle;
    }

    public String getCastText() {
        return castText;
    }

    public String getGenre() {
        return genre;
    }

    public Integer getDurationMinutes() {
        return durationMinutes;
    }

    public String getCandidateAuditoriumName() {
        return candidateAuditoriumName;
    }

    public String getFinalAuditoriumName() {
        return finalAuditoriumName;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public ScreeningState getState() {
        return state;
    }

    public String getConditionalNotes() {
        return conditionalNotes;
    }

    public Instant getFinalSubmittedAt() {
        return finalSubmittedAt;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public long getVersion() {
        return version;
    }

    public void updateDraft(
            String filmTitle,
            String castText,
            String genre,
            Integer durationMinutes,
            String candidateAuditoriumName,
            Instant startTime,
            Instant endTime) {
        if (deletedAt != null || state != ScreeningState.CREATED) {
            throw new IllegalStateException("Only an active CREATED Screening may be edited");
        }
        String normalizedFilmTitle = normalizeOptionalText(filmTitle, "filmTitle");
        String normalizedCastText = normalizeOptionalText(castText, "castText");
        String normalizedGenre = normalizeOptionalText(genre, "genre");
        Integer validatedDuration = validateDuration(durationMinutes);
        String normalizedCandidate = normalizeOptionalText(
                candidateAuditoriumName, "candidateAuditoriumName");
        validateInterval(startTime, endTime, validatedDuration);
        this.filmTitle = normalizedFilmTitle;
        this.castText = normalizedCastText;
        this.genre = normalizedGenre;
        this.durationMinutes = validatedDuration;
        this.candidateAuditoriumName = normalizedCandidate;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public void withdraw(Instant withdrawnAt) {
        if (deletedAt != null) {
            throw new IllegalStateException("An inactive Screening cannot be withdrawn again");
        }
        if (state != ScreeningState.CREATED && state != ScreeningState.SUBMITTED) {
            throw new IllegalStateException("Only CREATED or SUBMITTED Screenings may be withdrawn");
        }
        deletedAt = requireNonNull(withdrawnAt, "withdrawnAt");
    }

    public void submit() {
        if (deletedAt != null || state != ScreeningState.CREATED) {
            throw new IllegalStateException("Only an active CREATED Screening may be submitted");
        }
        state = ScreeningState.SUBMITTED;
    }

    public void rejectForMissingFinalSubmission(String reason) {
        if (state != ScreeningState.APPROVED || finalSubmittedAt != null) {
            throw new IllegalStateException(
                    "Only an APPROVED Screening without final submission may be automatically rejected");
        }
        state = ScreeningState.REJECTED;
        rejectionReason = requireNonBlank(reason, "reason");
    }
}
