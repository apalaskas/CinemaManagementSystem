package com.example.cinema.common.error;

public final class DuplicateNameException extends ConflictException {
    public DuplicateNameException() {
        super("DUPLICATE_NAME", "A resource with that name already exists.");
    }
}
