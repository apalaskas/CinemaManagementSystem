package com.example.cinema.common.error;

public final class ProgramRoleExistsException extends ConflictException {
    public ProgramRoleExistsException() {
        super("PROGRAM_ROLE_EXISTS", "The user already has that Program role.");
    }
}
