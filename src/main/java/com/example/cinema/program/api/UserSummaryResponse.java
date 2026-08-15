package com.example.cinema.program.api;

import java.util.UUID;

public record UserSummaryResponse(UUID userId, String username, String fullName) {
}
