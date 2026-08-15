package com.example.cinema.user.authentication;

import java.util.Locale;
import java.util.Optional;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.cinema.user.repository.UserRepository;

@Component
public class SharedDatabaseAuthenticationAdapter implements AuthenticationAdapter {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SharedDatabaseAuthenticationAdapter(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Optional<AuthenticatedUserIdentity> authenticate(String username, CharSequence rawPassword) {
        if (username == null || rawPassword == null) {
            return Optional.empty();
        }
        String normalizedUsername = username.trim().toLowerCase(Locale.ROOT);
        if (normalizedUsername.isEmpty()) {
            return Optional.empty();
        }
        return userRepository.findByUsername(normalizedUsername)
                .filter(user -> isBcryptHash(user.getPasswordHashOrExternalReference()))
                .filter(user -> passwordEncoder.matches(rawPassword, user.getPasswordHashOrExternalReference()))
                .map(user -> new AuthenticatedUserIdentity(user.getId(), user.getUsername(), user.getFullName()));
    }

    private static boolean isBcryptHash(String value) {
        return value != null && value.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}");
    }
}
