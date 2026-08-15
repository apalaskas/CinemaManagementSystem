package com.example.cinema.common.error;

public final class OptimisticConcurrencyConflictException extends ConflictException {
    public OptimisticConcurrencyConflictException() {
        super("CONCURRENT_MODIFICATION",
                "The resource changed while the request was being processed.");
    }
}
