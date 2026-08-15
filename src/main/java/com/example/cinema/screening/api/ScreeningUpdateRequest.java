package com.example.cinema.screening.api;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonAnySetter;

public final class ScreeningUpdateRequest {

    private String filmTitle;
    private String cast;
    private String genre;
    private Integer durationMinutes;
    private String candidateAuditoriumName;
    private Instant startTime;
    private Instant endTime;
    private boolean filmTitleSupplied;
    private boolean castSupplied;
    private boolean genreSupplied;
    private boolean durationMinutesSupplied;
    private boolean candidateAuditoriumNameSupplied;
    private boolean startTimeSupplied;
    private boolean endTimeSupplied;

    public String getFilmTitle() { return filmTitle; }
    public void setFilmTitle(String filmTitle) {
        this.filmTitle = filmTitle;
        this.filmTitleSupplied = true;
    }
    public String getCast() { return cast; }
    public void setCast(String cast) {
        this.cast = cast;
        this.castSupplied = true;
    }
    public String getGenre() { return genre; }
    public void setGenre(String genre) {
        this.genre = genre;
        this.genreSupplied = true;
    }
    public Integer getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Integer durationMinutes) {
        this.durationMinutes = durationMinutes;
        this.durationMinutesSupplied = true;
    }
    public String getCandidateAuditoriumName() { return candidateAuditoriumName; }
    public void setCandidateAuditoriumName(String candidateAuditoriumName) {
        this.candidateAuditoriumName = candidateAuditoriumName;
        this.candidateAuditoriumNameSupplied = true;
    }
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) {
        this.startTime = startTime;
        this.startTimeSupplied = true;
    }
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
        this.endTimeSupplied = true;
    }

    public boolean isFilmTitleSupplied() { return filmTitleSupplied; }
    public boolean isCastSupplied() { return castSupplied; }
    public boolean isGenreSupplied() { return genreSupplied; }
    public boolean isDurationMinutesSupplied() { return durationMinutesSupplied; }
    public boolean isCandidateAuditoriumNameSupplied() { return candidateAuditoriumNameSupplied; }
    public boolean isStartTimeSupplied() { return startTimeSupplied; }
    public boolean isEndTimeSupplied() { return endTimeSupplied; }

    public boolean hasAnySuppliedField() {
        return filmTitleSupplied || castSupplied || genreSupplied || durationMinutesSupplied
                || candidateAuditoriumNameSupplied || startTimeSupplied || endTimeSupplied;
    }

    public void setScreeningId(Object ignored) { rejectReadOnlyField("screeningId"); }
    public void setProgramId(Object ignored) { rejectReadOnlyField("programId"); }
    public void setSubmitterUserId(Object ignored) { rejectReadOnlyField("submitterUserId"); }
    public void setSubmitter(Object ignored) { rejectReadOnlyField("submitter"); }
    public void setHandlerUserId(Object ignored) { rejectReadOnlyField("handlerUserId"); }
    public void setHandler(Object ignored) { rejectReadOnlyField("handler"); }
    public void setState(Object ignored) { rejectReadOnlyField("state"); }
    public void setFinalAuditoriumName(Object ignored) { rejectReadOnlyField("finalAuditoriumName"); }
    public void setFinalAuditorium(Object ignored) { rejectReadOnlyField("finalAuditorium"); }
    public void setFinalSubmittedAt(Object ignored) { rejectReadOnlyField("finalSubmittedAt"); }
    public void setRejectionReason(Object ignored) { rejectReadOnlyField("rejectionReason"); }
    public void setReview(Object ignored) { rejectReadOnlyField("review"); }
    public void setConditionalNotes(Object ignored) { rejectReadOnlyField("conditionalNotes"); }
    public void setCreatedAt(Object ignored) { rejectReadOnlyField("createdAt"); }
    public void setDeletedAt(Object ignored) { rejectReadOnlyField("deletedAt"); }
    public void setId(Object ignored) { rejectReadOnlyField("id"); }
    public void setVersion(Object ignored) { rejectReadOnlyField("version"); }

    @JsonAnySetter
    public void rejectUnknownField(String field, Object ignored) {
        rejectReadOnlyField(field);
    }

    private static void rejectReadOnlyField(String field) {
        throw new IllegalArgumentException(field + " is read-only");
    }
}
