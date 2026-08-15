package com.example.cinema.user.authentication;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.example.cinema.user.repository.UserRepository;

@Component
public class SharedDatabaseAuthenticationAdapter implements AuthenticationAdapter {

    private final UserRepository userRepository;

    public SharedDatabaseAuthenticationAdapter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Optional<AuthenticatedUserIdentity> findByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(user -> new AuthenticatedUserIdentity(
                        user.getId(),
                        user.getUsername(),
                        user.getPasswordHashOrExternalReference(),
                        user.getFullName()));
    }
}
