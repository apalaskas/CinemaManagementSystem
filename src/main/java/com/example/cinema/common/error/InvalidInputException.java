package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public class InvalidInputException extends ApplicationException {
    public InvalidInputException(String errorCode, String safeDetail) {
        super(HttpStatus.BAD_REQUEST, errorCode, safeDetail);
    }
}
