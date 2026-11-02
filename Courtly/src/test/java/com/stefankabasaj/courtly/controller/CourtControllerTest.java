package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.SecurityConfig;
import com.stefankabasaj.courtly.dto.request.CourtRequest;
import com.stefankabasaj.courtly.dto.response.CourtAvailabilityResponse;
import com.stefankabasaj.courtly.dto.response.CourtResponse;
import com.stefankabasaj.courtly.service.CourtAvailabilityService;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import com.stefankabasaj.courtly.service.CourtService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ActiveProfiles("test")
@WebMvcTest(CourtController.class)
// load the real security rules, otherwise the slice runs Spring's default chain (CSRF on, everything locked)
@Import(SecurityConfig.class)
@DisplayName("CourtController")
class CourtControllerTest {

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean CourtService courtService;
    @MockitoBean CourtAvailabilityService availabilityService;
    @MockitoBean JwtService   jwtService;

    private final CourtResponse sample = new CourtResponse(
            UUID.randomUUID(), "Court A", "CLAY", new BigDecimal("25.00"), true);

    @Test
    @WithMockUser
    @DisplayName("GET /courts - 200 with court list")
    void getAll_returns200() throws Exception {
        when(courtService.getAllActiveCourts()).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/v1/courts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Court A"))
                .andExpect(jsonPath("$[0].surface").value("CLAY"));
    }

    @Test
    @DisplayName("GET /courts - 401 without auth")
    void getAll_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/courts"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/v1/courts"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /courts - ADMIN creates court, 201")
    void create_admin_returns201() throws Exception {
        CourtRequest req = new CourtRequest("Court A", Court.Surface.CLAY, new BigDecimal("25.00"));
        when(courtService.createCourt(any())).thenReturn(sample);

        mockMvc.perform(post("/api/v1/courts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Court A"));
    }

    @Test
    @WithMockUser(roles = "USER")
    @DisplayName("POST /courts - USER gets 403")
    void create_user_returns403() throws Exception {
        CourtRequest req = new CourtRequest("Court A", Court.Surface.CLAY, new BigDecimal("25.00"));

        mockMvc.perform(post("/api/v1/courts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /courts - 409 on duplicate name")
    void create_duplicate_returns409() throws Exception {
        CourtRequest req = new CourtRequest("Court A", Court.Surface.CLAY, new BigDecimal("25.00"));
        when(courtService.createCourt(any())).thenThrow(new ConflictException("Already exists"));

        mockMvc.perform(post("/api/v1/courts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /courts/{id} - 404 when not found")
    void getById_notFound_returns404() throws Exception {
        UUID id = UUID.randomUUID();
        when(courtService.getCourtById(id))
                .thenThrow(new ResourceNotFoundException("Court", id));

        mockMvc.perform(get("/api/v1/courts/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    @DisplayName("POST /courts - 422 when name blank")
    void create_blankName_returns422() throws Exception {
        CourtRequest req = new CourtRequest("", Court.Surface.CLAY, new BigDecimal("25.00"));

        mockMvc.perform(post("/api/v1/courts").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.fieldErrors.name").exists());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /courts/{id}/availability - 200 with hourly slots")
    void availability_returns200() throws Exception {
        UUID id = sample.id();
        LocalDate date = LocalDate.of(2030, 6, 1);
        var slot = new CourtAvailabilityResponse.Slot(date.atTime(7, 0), date.atTime(8, 0), true);
        when(availabilityService.getAvailability(id, date))
                .thenReturn(new CourtAvailabilityResponse(id, "Court A", date, List.of(slot)));

        mockMvc.perform(get("/api/v1/courts/{id}/availability", id).param("date", "2030-06-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value("2030-06-01"))
                .andExpect(jsonPath("$.slots[0].startTime").value("2030-06-01T07:00:00"))
                .andExpect(jsonPath("$.slots[0].available").value(true));
    }

    @Test
    @WithMockUser
    @DisplayName("GET /courts/{id}/availability - 400 without a date")
    void availability_missingDate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/courts/{id}/availability", sample.id()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser
    @DisplayName("GET /courts/{id}/availability - 400 for a malformed date")
    void availability_badDate_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/courts/{id}/availability", sample.id()).param("date", "tomorrow"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /courts?includeInactive=true - ADMIN gets all courts")
    void getAll_includeInactive_admin() throws Exception {
        when(courtService.getAllCourts()).thenReturn(List.of(sample));

        mockMvc.perform(get("/api/v1/courts").param("includeInactive", "true")
                        .with(authentication(auth("ADMIN"))))
                .andExpect(status().isOk());
        verify(courtService).getAllCourts();
    }

    @Test
    @DisplayName("GET /courts?includeInactive=true - 403 for USER")
    void getAll_includeInactive_user() throws Exception {
        mockMvc.perform(get("/api/v1/courts").param("includeInactive", "true")
                        .with(authentication(auth("USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PATCH /courts/{id}/activate - ADMIN 200, USER 403")
    void activate() throws Exception {
        when(courtService.activateCourt(sample.id())).thenReturn(sample);

        mockMvc.perform(patch("/api/v1/courts/{id}/activate", sample.id())
                        .with(authentication(auth("ADMIN"))))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/courts/{id}/activate", sample.id())
                        .with(authentication(auth("USER"))))
                .andExpect(status().isForbidden());
    }

    private UsernamePasswordAuthenticationToken auth(String role) {
        return new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(UUID.randomUUID(), "stefan@courtly.com", role), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
    }
}
