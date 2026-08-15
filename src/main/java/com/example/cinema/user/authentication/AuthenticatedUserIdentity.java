package com.example.cinema.user.authentication;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;

public record AuthenticatedUserIdentity(
        UUID userId,
        String username,
        String fullName) {

    public List<GrantedAuthority> authorities() {
        return List.of();
    }
}
