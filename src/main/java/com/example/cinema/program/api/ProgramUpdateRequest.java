package com.example.cinema.program.api;

import java.time.LocalDate;

public final class ProgramUpdateRequest {

    private String name;
    private String description;
    private LocalDate startDate;
    private LocalDate endDate;
    private boolean nameSupplied;
    private boolean descriptionSupplied;
    private boolean startDateSupplied;
    private boolean endDateSupplied;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        this.nameSupplied = true;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionSupplied = true;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
        this.startDateSupplied = true;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
        this.endDateSupplied = true;
    }

    public boolean isNameSupplied() {
        return nameSupplied;
    }

    public boolean isDescriptionSupplied() {
        return descriptionSupplied;
    }

    public boolean isStartDateSupplied() {
        return startDateSupplied;
    }

    public boolean isEndDateSupplied() {
        return endDateSupplied;
    }

    public boolean hasAnySuppliedField() {
        return nameSupplied || descriptionSupplied || startDateSupplied || endDateSupplied;
    }

    public void setState(Object ignored) {
        rejectReadOnlyField("state");
    }

    public void setCreator(Object ignored) {
        rejectReadOnlyField("creator");
    }

    public void setCreatorUserId(Object ignored) {
        rejectReadOnlyField("creatorUserId");
    }

    public void setCreatedAt(Object ignored) {
        rejectReadOnlyField("createdAt");
    }

    public void setRoles(Object ignored) {
        rejectReadOnlyField("roles");
    }

    public void setProgramId(Object ignored) {
        rejectReadOnlyField("programId");
    }

    public void setId(Object ignored) {
        rejectReadOnlyField("id");
    }

    public void setUserId(Object ignored) {
        rejectReadOnlyField("userId");
    }

    public void setVersion(Object ignored) {
        rejectReadOnlyField("version");
    }

    private static void rejectReadOnlyField(String field) {
        throw new IllegalArgumentException(field + " is read-only");
    }
}
