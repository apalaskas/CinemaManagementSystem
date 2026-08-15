package com.example.cinema.common.error;

public final class SchedulingConflictException extends ConflictException {
    public SchedulingConflictException() {
        super("SCHEDULING_CONFLICT", "The requested final schedule conflicts with another screening.");
    }
}
