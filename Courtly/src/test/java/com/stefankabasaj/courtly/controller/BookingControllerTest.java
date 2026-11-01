package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.SecurityConfig;
import com.stefankabasaj.courtly.dto.request.BookingRequest;
import com.stefankabasaj.courtly.dto.response.BookingResponse;
import com.stefankabasaj.courtly.dto.response.PageResponse;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(BookingController.class)
// load the real security rules, otherwise the slice runs Spring's default chain (CSRF on, everything locked)
@Import(SecurityConfig.class)
@DisplayName("BookingController")
class BookingControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean BookingService bookingService;
    @MockitoBean JwtService     jwtService;

    private UUID userId;
    private BookingResponse sample;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        LocalDateTime start = LocalDateTime.now().plusDays(1)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        sample = new BookingResponse(
                UUID.randomUUID(), userId, "stefan@courtly.com",
                UUID.randomUUID(), "Court A", "CLAY",
                start, start.plusHours(2), 2,
                new BigDecimal("25.00"), new BigDecimal("50.00"),
                "PENDING", null, true,
                LocalDateTime.now(), LocalDateTime.now()
        );
    }

    private UsernamePasswordAuthenticationToken userAuth(String role) {
        AuthenticatedUser principal = new AuthenticatedUser(userId, "stefan@courtly.com", role);
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }

    @Test
    @DisplayName("POST /bookings - 201 Created")
    void create_returns201() throws Exception {
        BookingRequest req = new BookingRequest(
                UUID.randomUUID(),
                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                LocalDateTime.now().plusDays(1).withHour(12).withMinute(0).withSecond(0).withNano(0)
        );
        when(bookingService.createBooking(any(), any())).thenReturn(sample);

        mockMvc.perform(post("/api/v1/bookings")
                        .with(authentication(userAuth("USER"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalPrice").value(50.00))
                .andExpect(jsonPath("$.durationHours").value(2))
                .andExpect(jsonPath("$.courtName").value("Court A"));
    }

    @Test
    @DisplayName("POST /bookings - 409 when slot taken")
    void create_conflict_returns409() throws Exception {
        BookingRequest req = new BookingRequest(
                UUID.randomUUID(),
                LocalDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0),
                LocalDateTime.now().plusDays(1).withHour(11).withMinute(0).withSecond(0).withNano(0)
        );
        when(bookingService.createBooking(any(), any()))
                .thenThrow(new ConflictException("Court already booked"));

        mockMvc.perform(post("/api/v1/bookings")
                        .with(authentication(userAuth("USER"))).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("GET /bookings/my - 200 with user's bookings")
    void getMyBookings_returns200() throws Exception {
        when(bookingService.getMyBookings(userId)).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/v1/bookings/my")
                        .with(authentication(userAuth("USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].courtName").value("Court A"))
                .andExpect(jsonPath("$[0].userEmail").value("stefan@courtly.com"));
    }

    @Test
    @DisplayName("GET /bookings/{id} - 404 when not found")
    void getById_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(bookingService.getBookingById(any(), any()))
                .thenThrow(new ResourceNotFoundException("Booking", id));

        mockMvc.perform(get("/api/v1/bookings/{id}", id)
                        .with(authentication(userAuth("USER"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("GET /bookings - 200 paginated for ADMIN")
    void getAllBookings_admin_returns200() throws Exception {
        PageResponse<BookingResponse> page = PageResponse.of(List.of(sample), 0, 20, 1L);
        when(bookingService.getAllBookings(0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/bookings?page=0&size=20")
                        .with(authentication(userAuth("ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.last").value(true));
    }

    @Test
    @DisplayName("GET /bookings - 403 for USER role")
    void getAllBookings_user_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/bookings")
                        .with(authentication(userAuth("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /bookings - 401 without auth")
    void create_noAuth_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
