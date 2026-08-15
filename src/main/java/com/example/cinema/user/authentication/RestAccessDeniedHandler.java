package com.example.cinema.user.authentication;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import com.example.cinema.common.error.ForbiddenException;
import com.example.cinema.common.error.ProblemResponseWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ProblemResponseWriter problemWriter;

    public RestAccessDeniedHandler(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException cause)
            throws IOException, ServletException {
        problemWriter.write(request, response, new ForbiddenException());
    }
}
