package com.stefankabasaj.courtly.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.dto.response.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Errors raised inside the security filter chain never reach
 * GlobalExceptionHandler, so without this they come back with an empty body -
 * and unauthenticated requests would get 403 instead of 401.
 * This writes the same JSON shape as every other error in the API.
 */
@RequiredArgsConstructor
public class ApiSecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    // no valid token
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Unauthorized",
                "Authentication is required to access this resource");
    }

    // valid token, wrong role
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException ex) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Forbidden",
                "You don't have permission to access this resource");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(),
                ErrorResponse.of(status.value(), error, message, request.getRequestURI()));
    }
}
