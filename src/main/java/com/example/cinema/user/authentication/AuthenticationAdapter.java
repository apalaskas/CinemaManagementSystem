package com.example.cinema.user.authentication;

import java.util.Optional;

public interface AuthenticationAdapter {

    Optional<AuthenticatedUserIdentity> findByUsername(String username);
}
