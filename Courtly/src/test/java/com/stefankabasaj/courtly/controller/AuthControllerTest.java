package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.SecurityConfig;
import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.TooManyRequestsException;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(AuthController.class)
// load the real security rules, otherwise the slice runs Spring's default chain (CSRF on, everything locked)
@Import(SecurityConfig.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean AuthService authService;
    @MockitoBean JwtService  jwtService;

    @Test
    @DisplayName("POST /auth/register - 201 with user data")
    void register_returns201() throws Exception {
        RegisterRequest req = new RegisterRequest("stefan@courtly.com", "Password1!");
        UserResponse    res = new UserResponse(UUID.randomUUID(), "stefan@courtly.com",
                "USER", true, LocalDateTime.now());

        when(authService.register(any())).thenReturn(res);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("stefan@courtly.com"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @DisplayName("POST /auth/register - 422 when email invalid")
    void register_invalidEmail_returns422() throws Exception {
        RegisterRequest req = new RegisterRequest("not-an-email", "Password1!");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    @DisplayName("POST /auth/register - 409 when email taken")
    void register_duplicate_returns409() throws Exception {
        RegisterRequest req = new RegisterRequest("stefan@courtly.com", "Password1!");
        when(authService.register(any()))
                .thenThrow(new ConflictException("Email already registered"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("POST /auth/login - 200 with token")
    void login_returns200() throws Exception {
        LoginRequest req = new LoginRequest("stefan@courtly.com", "Password1!");
        when(authService.login(any(), any()))
                .thenReturn(AuthResponse.of("jwt.token.here", Instant.parse("2030-06-01T10:00:00Z"), "stefan@courtly.com", "USER"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt.token.here"))
                .andExpect(jsonPath("$.type").value("Bearer"))
                .andExpect(jsonPath("$.expiresAt").value("2030-06-01T10:00:00Z"));
    }

    @Test
    @DisplayName("POST /auth/login - 422 when password blank")
    void login_blankPassword_returns422() throws Exception {
        LoginRequest req = new LoginRequest("stefan@courtly.com", "");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("POST /auth/register - 400 when the body is not valid JSON")
    void register_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /auth/register - 422 when password is too long for BCrypt")
    void register_passwordTooLong_returns422() throws Exception {
        RegisterRequest req = new RegisterRequest("stefan@courtly.com", "a".repeat(73));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    @DisplayName("POST /auth/login - 429 with Retry-After when blocked")
    void login_blocked_returns429() throws Exception {
        when(authService.login(any(), any()))
                .thenThrow(new TooManyRequestsException("Too many failed login attempts", 600));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("stefan@courtly.com", "Password1!"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "600"))
                .andExpect(jsonPath("$.status").value(429));
    }

    @Test
    @DisplayName("POST /auth/register - 422 for a password under 72 characters but over 72 bytes")
    void register_multibytePasswordTooLong_returns422() throws Exception {
        // 40 Cyrillic letters = 40 characters, 80 bytes
        RegisterRequest req = new RegisterRequest("stefan@courtly.com", "ш".repeat(40));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    @DisplayName("POST /auth/register - accented password within 72 bytes is fine")
    void register_accentedPassword_returns201() throws Exception {
        RegisterRequest req = new RegisterRequest("stefan@courtly.com", "čćšđž-lozinka-1");
        when(authService.register(any())).thenReturn(new UserResponse(
                UUID.randomUUID(), "stefan@courtly.com", "USER", true, LocalDateTime.now()));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());
    }
}
