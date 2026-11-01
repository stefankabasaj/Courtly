package com.stefankabasaj.courtly.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.SecurityConfig;
import com.stefankabasaj.courtly.dto.request.CourtRequest;
import com.stefankabasaj.courtly.dto.response.CourtResponse;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.security.JwtService;
import com.stefankabasaj.courtly.service.CourtService;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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

    @MockBean CourtService courtService;
    @MockBean JwtService   jwtService;

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
}
