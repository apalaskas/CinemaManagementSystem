package com.example.cinema.common.error;

import org.springframework.http.HttpStatus;

public abstract class ApplicationException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final boolean retryable;

    protected ApplicationException(HttpStatus status, String errorCode, String safeDetail) {
        this(status, errorCode, safeDetail, false);
    }

    protected ApplicationException(HttpStatus status, String errorCode, String safeDetail, boolean retryable) {
        super(safeDetail);
        this.status = status;
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public String safeDetail() {
        return getMessage();
    }

    public boolean retryable() {
        return retryable;
    }
}
