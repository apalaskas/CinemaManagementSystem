package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public class ForbiddenException extends ApplicationException {
    public ForbiddenException() {
        super(HttpStatus.FORBIDDEN, "FORBIDDEN", "You are not allowed to perform this operation.");
    }
}
