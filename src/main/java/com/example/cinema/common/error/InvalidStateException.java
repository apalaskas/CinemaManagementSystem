package com.example.cinema.common.error;

public final class InvalidStateException extends ConflictException {
    public InvalidStateException() {
        super("INVALID_STATE", "The operation is not valid in the current lifecycle state.");
    }
}
