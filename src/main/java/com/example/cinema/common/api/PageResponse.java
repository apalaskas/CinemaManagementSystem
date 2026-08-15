package com.example.cinema.common.api;

import java.util.List;

public record PageResponse<T>(
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<T> content) {

    public PageResponse {
        content = List.copyOf(content);
    }
}
