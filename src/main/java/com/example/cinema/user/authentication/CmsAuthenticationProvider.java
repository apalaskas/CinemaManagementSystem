package com.example.cinema.user.authentication;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class CmsAuthenticationProvider implements AuthenticationProvider {

    private final AuthenticationAdapter authenticationAdapter;

    public CmsAuthenticationProvider(AuthenticationAdapter authenticationAdapter) {
        this.authenticationAdapter = authenticationAdapter;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        Object credentials = authentication.getCredentials();
        if (!(credentials instanceof CharSequence rawPassword)) {
            throw new BadCredentialsException("Invalid credentials");
        }
        AuthenticatedUserIdentity identity = authenticationAdapter
                .authenticate(authentication.getName(), rawPassword)
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return UsernamePasswordAuthenticationToken.authenticated(identity, null, identity.authorities());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
