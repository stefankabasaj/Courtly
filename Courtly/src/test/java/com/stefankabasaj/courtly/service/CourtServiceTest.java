package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.CourtRequest;
import com.stefankabasaj.courtly.dto.response.CourtResponse;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.CourtRepository;
import org.junit.jupiter.api.DisplayName;
import org.springframework.test.context.ActiveProfiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@ExtendWith(MockitoExtension.class)
@DisplayName("CourtService")
class CourtServiceTest {

    @Mock CourtRepository courtRepository;
    @InjectMocks CourtService courtService;

    private Court buildCourt(boolean active) {
        return Court.builder().id(UUID.randomUUID())
                .name("Court A").surface(Court.Surface.CLAY)
                .pricePerHour(new BigDecimal("25.00")).active(active).build();
    }

    @Test
    @DisplayName("createCourt - success")
    void create_success() {
        Court court = buildCourt(true);
        when(courtRepository.existsByName("Court A")).thenReturn(false);
        when(courtRepository.save(any())).thenReturn(court);

        CourtResponse resp = courtService.createCourt(
                new CourtRequest("Court A", Court.Surface.CLAY, new BigDecimal("25.00")));

        assertThat(resp.name()).isEqualTo("Court A");
        assertThat(resp.active()).isTrue();
    }

    @Test
    @DisplayName("createCourt - duplicate name throws ConflictException")
    void create_duplicate_throws() {
        when(courtRepository.existsByName("Court A")).thenReturn(true);

        assertThatThrownBy(() -> courtService.createCourt(
                new CourtRequest("Court A", Court.Surface.CLAY, new BigDecimal("25.00"))))
                .isInstanceOf(ConflictException.class);

        verify(courtRepository, never()).save(any());
    }

    @Test
    @DisplayName("getCourtById - not found throws ResourceNotFoundException")
    void getById_notFound_throws() {
        UUID id = UUID.randomUUID();
        when(courtRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> courtService.getCourtById(id))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Court");
    }

    @Test
    @DisplayName("deactivateCourt - already inactive throws BusinessException")
    void deactivate_alreadyInactive_throws() {
        Court court = buildCourt(false);
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));

        assertThatThrownBy(() -> courtService.deactivateCourt(court.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already deactivated");
    }

    @Test
    @DisplayName("getAllActiveCourts - returns only active")
    void getAll_returnsActive() {
        when(courtRepository.findAllByActiveTrue()).thenReturn(List.of(buildCourt(true)));

        List<CourtResponse> result = courtService.getAllActiveCourts();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).active()).isTrue();
    }

    @Test
    @DisplayName("updateCourt - taking another court's name throws ConflictException")
    void update_nameTaken_throws() {
        Court court = buildCourt(true);
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
        when(courtRepository.existsByNameAndIdNot("Court B", court.getId())).thenReturn(true);

        assertThatThrownBy(() -> courtService.updateCourt(court.getId(),
                new CourtRequest("Court B", Court.Surface.CLAY, new BigDecimal("25.00"))))
                .isInstanceOf(ConflictException.class);

        verify(courtRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateCourt - keeps its own name and updates the price")
    void update_sameName_success() {
        Court court = buildCourt(true);
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
        when(courtRepository.existsByNameAndIdNot("Court A", court.getId())).thenReturn(false);
        when(courtRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CourtResponse resp = courtService.updateCourt(court.getId(),
                new CourtRequest("  Court A ", Court.Surface.HARD, new BigDecimal("30.00")));

        assertThat(resp.name()).isEqualTo("Court A");
        assertThat(resp.surface()).isEqualTo("HARD");
        assertThat(resp.pricePerHour()).isEqualByComparingTo("30.00");
    }
}
