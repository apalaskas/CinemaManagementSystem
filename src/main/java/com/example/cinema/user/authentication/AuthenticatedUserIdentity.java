package com.example.cinema.user.authentication;

import java.util.UUID;

public record AuthenticatedUserIdentity(
        UUID userId,
        String username,
        String passwordHashOrExternalReference,
        String fullName) {
}
