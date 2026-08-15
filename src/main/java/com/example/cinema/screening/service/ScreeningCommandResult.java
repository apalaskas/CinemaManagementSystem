package com.example.cinema.screening.service;

public record ScreeningCommandResult<T>(int status, T body, boolean replayed) {
}
