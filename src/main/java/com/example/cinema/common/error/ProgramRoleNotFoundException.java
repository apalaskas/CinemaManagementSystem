package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public final class ProgramRoleNotFoundException extends ApplicationException {
    public ProgramRoleNotFoundException() {
        super(HttpStatus.NOT_FOUND, "PROGRAM_ROLE_NOT_FOUND",
                "The requested Program role assignment was not found.");
    }
}
