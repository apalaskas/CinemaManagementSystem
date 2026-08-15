package com.example.cinema.common.error;

public final class ReviewAlreadyExistsException extends ConflictException {
    public ReviewAlreadyExistsException() {
        super("REVIEW_ALREADY_EXISTS", "A Review already exists for this Screening.");
    }
}
