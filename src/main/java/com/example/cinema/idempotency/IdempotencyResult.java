package com.example.cinema.idempotency;

public record IdempotencyResult(int status, String body, boolean replayed) {
}
