package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.SecurityConfig;
import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ActiveProfiles("test")
@WebMvcTest(PaymentController.class)
// load the real security rules, otherwise the slice runs Spring's default chain (CSRF on, everything locked)
@Import(SecurityConfig.class)
@DisplayName("PaymentController")
class PaymentControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean PaymentService paymentService;
    @MockBean JwtService     jwtService;

    private UUID bookingId;
    private AuthenticatedUser principal;
    private PaymentResponse sample;

    @BeforeEach
    void setUp() {
        bookingId = UUID.randomUUID();
        principal = new AuthenticatedUser(UUID.randomUUID(), "stefan@courtly.com", "USER");
        sample = new PaymentResponse(
                UUID.randomUUID(), bookingId, new BigDecimal("50.00"),
                "SUCCESS", "CREDIT_CARD", null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private UsernamePasswordAuthenticationToken auth() {
        return new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    @DisplayName("POST /payments/{bookingId} - 200 and the caller is the payer")
    void pay_returns200() throws Exception {
        when(paymentService.processPayment(eq(bookingId), eq(principal.userId()), any()))
                .thenReturn(sample);

        mockMvc.perform(post("/api/v1/payments/{id}", bookingId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PaymentRequest("CREDIT_CARD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    @DisplayName("GET /payments/booking/{id} - passes the caller to the service for the ownership check")
    void getByBooking_passesPrincipal() throws Exception {
        when(paymentService.getPaymentByBookingId(eq(bookingId), any())).thenReturn(sample);

        mockMvc.perform(get("/api/v1/payments/booking/{id}", bookingId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(bookingId.toString()));

        verify(paymentService).getPaymentByBookingId(bookingId, principal);
    }

    @Test
    @DisplayName("GET /payments/booking/{id} - 404 for someone else's booking")
    void getByBooking_foreign_returns404() throws Exception {
        when(paymentService.getPaymentByBookingId(eq(bookingId), any()))
                .thenThrow(new ResourceNotFoundException("Booking", bookingId));

        mockMvc.perform(get("/api/v1/payments/booking/{id}", bookingId)
                        .with(authentication(auth())))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /payments/booking/{id} - 401 without auth")
    void getByBooking_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/payments/booking/{id}", bookingId))
                .andExpect(status().isUnauthorized());
    }
}
