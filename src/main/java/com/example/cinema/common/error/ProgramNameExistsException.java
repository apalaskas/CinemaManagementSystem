package com.example.cinema.common.error;

public final class ProgramNameExistsException extends ConflictException {
    public ProgramNameExistsException() {
        super("PROGRAM_NAME_EXISTS", "A Program with that name already exists.");
    }
}
