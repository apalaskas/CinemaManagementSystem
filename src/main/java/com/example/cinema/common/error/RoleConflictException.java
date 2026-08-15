package com.example.cinema.common.error;

public final class RoleConflictException extends ConflictException {
    public RoleConflictException() {
        super("ROLE_CONFLICT", "The requested Program role conflicts with the existing role.");
    }
}
