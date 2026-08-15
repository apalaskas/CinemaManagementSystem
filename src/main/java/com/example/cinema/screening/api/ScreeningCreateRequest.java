package com.example.cinema.screening.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ScreeningCreateRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank when supplied")
        @Size(max = 255, message = "must contain at most 255 characters")
        String filmTitle,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank when supplied")
        String cast,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank when supplied")
        @Size(max = 255, message = "must contain at most 255 characters")
        String genre,
        @Positive(message = "must be positive when supplied")
        Integer durationMinutes,
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank when supplied")
        @Size(max = 255, message = "must contain at most 255 characters")
        String candidateAuditoriumName,
        Instant startTime,
        Instant endTime) {

    public void setScreeningId(Object ignored) { rejectReadOnlyField("screeningId"); }
    public void setProgramId(Object ignored) { rejectReadOnlyField("programId"); }
    public void setSubmitterUserId(Object ignored) { rejectReadOnlyField("submitterUserId"); }
    public void setHandler(Object ignored) { rejectReadOnlyField("handler"); }
    public void setState(Object ignored) { rejectReadOnlyField("state"); }
    public void setFinalAuditoriumName(Object ignored) { rejectReadOnlyField("finalAuditoriumName"); }
    public void setFinalSubmittedAt(Object ignored) { rejectReadOnlyField("finalSubmittedAt"); }
    public void setRejectionReason(Object ignored) { rejectReadOnlyField("rejectionReason"); }
    public void setReview(Object ignored) { rejectReadOnlyField("review"); }
    public void setCreatedAt(Object ignored) { rejectReadOnlyField("createdAt"); }
    public void setDeletedAt(Object ignored) { rejectReadOnlyField("deletedAt"); }
    public void setVersion(Object ignored) { rejectReadOnlyField("version"); }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        rejectReadOnlyField(field);
    }

    private static void rejectReadOnlyField(String field) {
        throw new IllegalArgumentException(field + " is read-only");
    }
}
