package com.example.cinema.user.authentication;

import java.util.Optional;

public interface AuthenticationAdapter {

    Optional<AuthenticatedUserIdentity> authenticate(String username, CharSequence rawPassword);
}
