package com.example.cinema.program.service;

public record ProgramCommandResult<T>(int status, T body, boolean replayed) {
}
