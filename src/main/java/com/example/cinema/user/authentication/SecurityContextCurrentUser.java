package com.example.cinema.user.authentication;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.example.cinema.common.error.AuthenticationRequiredException;

@Component
public class SecurityContextCurrentUser implements CurrentUser {

    @Override
    public Optional<AuthenticatedUserIdentity> optional() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUserIdentity identity)) {
            return Optional.empty();
        }
        return Optional.of(identity);
    }

    @Override
    public AuthenticatedUserIdentity require() {
        return optional().orElseThrow(AuthenticationRequiredException::new);
    }
}
