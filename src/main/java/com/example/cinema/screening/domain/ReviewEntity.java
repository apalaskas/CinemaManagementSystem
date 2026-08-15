package com.example.cinema.screening.domain;

import static com.example.cinema.common.domain.DomainAssertions.requireNonBlank;
import static java.util.Objects.requireNonNull;

import java.math.BigDecimal;
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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "review")
public class ReviewEntity {

    public static final int MAXIMUM_COMMENT_LENGTH = 4000;
    private static final BigDecimal MINIMUM_SCORE = new BigDecimal("0.00");
    private static final BigDecimal MAXIMUM_SCORE = new BigDecimal("10.00");

    @Id
    @JdbcTypeCode(SqlTypes.BINARY)
    @Column(name = "review_id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "screening_id", nullable = false, unique = true)
    private ScreeningEntity screening;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_user_id", nullable = false)
    private UserEntity staff;

    @Column(name = "numeric_score", nullable = false, precision = 4, scale = 2)
    private BigDecimal numericScore;

    @Column(name = "detailed_comments", nullable = false, columnDefinition = "text")
    private String detailedComments;

    @Column(name = "created_at", nullable = false, columnDefinition = "datetime(6)")
    private Instant createdAt;

    protected ReviewEntity() {
    }

    public ReviewEntity(
            UUID id,
            ScreeningEntity screening,
            UserEntity staff,
            BigDecimal numericScore,
            String detailedComments,
            Instant createdAt) {
        this.id = requireNonNull(id, "id");
        this.screening = requireNonNull(screening, "screening");
        this.staff = requireNonNull(staff, "staff");
        this.numericScore = validateScore(numericScore);
        this.detailedComments = validateComments(detailedComments);
        this.createdAt = requireNonNull(createdAt, "createdAt");
    }

    private static BigDecimal validateScore(BigDecimal score) {
        requireNonNull(score, "numericScore");
        if (score.compareTo(MINIMUM_SCORE) < 0 || score.compareTo(MAXIMUM_SCORE) > 0) {
            throw new IllegalArgumentException("numericScore must be between 0.00 and 10.00 inclusive");
        }
        if (score.scale() > 2) {
            throw new IllegalArgumentException("numericScore must have at most two decimal places");
        }
        return score;
    }

    private static String validateComments(String comments) {
        String normalized = requireNonBlank(comments, "detailedComments");
        if (normalized.length() > MAXIMUM_COMMENT_LENGTH) {
            throw new IllegalArgumentException(
                    "detailedComments must not exceed " + MAXIMUM_COMMENT_LENGTH + " characters");
        }
        return normalized;
    }

    public UUID getId() {
        return id;
    }

    public ScreeningEntity getScreening() {
        return screening;
    }

    public UserEntity getStaff() {
        return staff;
    }

    public BigDecimal getNumericScore() {
        return numericScore;
    }

    public String getDetailedComments() {
        return detailedComments;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
