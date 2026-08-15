package com.example.cinema.common.error;

public final class ProgramTransitionPrerequisiteException extends ConflictException {
    public ProgramTransitionPrerequisiteException(String safeDetail) {
        super("PROGRAM_TRANSITION_PREREQUISITE_FAILED", safeDetail);
    }
}
