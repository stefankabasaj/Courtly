package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.service.AuthService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(AuthController.class)
@DisplayName("AuthController")
class AuthControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AuthService authService;
    @MockBean JwtService  jwtService;

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
        when(authService.login(any()))
                .thenReturn(AuthResponse.of("jwt.token.here", "stefan@courtly.com", "USER"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt.token.here"))
                .andExpect(jsonPath("$.type").value("Bearer"));
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
}
