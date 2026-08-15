package com.example.cinema.common.error;

public final class IdempotencyConflictException extends ConflictException {
    public IdempotencyConflictException(String errorCode, String safeDetail, boolean retryable) {
        super(errorCode, safeDetail, retryable);
    }
}
