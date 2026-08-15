package com.example.cinema.common.error;

import java.util.List;

import com.example.cinema.common.error.ApiProblemFactory.FieldErrorDetail;

public final class FieldValidationException extends InvalidInputException {

    private final List<FieldErrorDetail> fieldErrors;

    public FieldValidationException(
            String errorCode,
            String safeDetail,
            List<FieldErrorDetail> fieldErrors) {
        super(errorCode, safeDetail);
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            throw new IllegalArgumentException("At least one field error is required.");
        }
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public List<FieldErrorDetail> fieldErrors() {
        return fieldErrors;
    }
}
