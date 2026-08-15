package com.example.cinema.common.domain;

public final class DomainAssertions {

    private DomainAssertions() {
    }

    public static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.strip();
    }

    public static String normalizeOptionalText(String value, String fieldName) {
        return value == null ? null : requireNonBlank(value, fieldName);
    }
}
