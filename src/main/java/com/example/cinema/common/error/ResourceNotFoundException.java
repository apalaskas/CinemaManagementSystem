package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends ApplicationException {
    public ResourceNotFoundException() {
        super(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource was not found.");
    }
}
