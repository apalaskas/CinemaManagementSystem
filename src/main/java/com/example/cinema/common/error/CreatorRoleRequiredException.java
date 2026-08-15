package com.example.cinema.common.error;

public final class CreatorRoleRequiredException extends ConflictException {
    public CreatorRoleRequiredException() {
        super("CREATOR_PROGRAMMER_REQUIRED", "The Program creator's PROGRAMMER role cannot be removed.");
    }
}
