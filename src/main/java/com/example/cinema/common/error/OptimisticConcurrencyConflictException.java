package com.example.cinema.common.error;

public final class OptimisticConcurrencyConflictException extends ConflictException {
    public OptimisticConcurrencyConflictException() {
        super("OPTIMISTIC_CONCURRENCY_CONFLICT",
                "The resource changed while the request was being processed.");
    }
}
