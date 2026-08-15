package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public class AuthenticationRequiredException extends ApplicationException {
    public AuthenticationRequiredException() {
        super(HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
    }
}
