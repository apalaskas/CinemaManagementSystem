package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApplicationException {
    public ConflictException(String errorCode, String safeDetail) {
        this(errorCode, safeDetail, false);
    }

    public ConflictException(String errorCode, String safeDetail, boolean retryable) {
        super(HttpStatus.CONFLICT, errorCode, safeDetail, retryable);
    }
}
