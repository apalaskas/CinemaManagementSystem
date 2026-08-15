package com.example.cinema.idempotency;

import static java.util.Objects.requireNonNull;

public record StoredCommandResponse(int status, String body) {
    public StoredCommandResponse {
        if (status < 200 || status > 299) {
            throw new IllegalArgumentException("Only successful command responses may be stored");
        }
        requireNonNull(body, "body");
    }
}
