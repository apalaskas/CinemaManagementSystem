package com.example.cinema.user.authentication;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.example.cinema.common.error.AuthenticationRequiredException;
import com.example.cinema.common.error.ProblemResponseWriter;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemResponseWriter problemWriter;

    public RestAuthenticationEntryPoint(ProblemResponseWriter problemWriter) {
        this.problemWriter = problemWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException cause)
            throws IOException, ServletException {
        response.setHeader("WWW-Authenticate", "Basic realm=\"cinema-management\"");
        problemWriter.write(request, response, new AuthenticationRequiredException());
    }
}
