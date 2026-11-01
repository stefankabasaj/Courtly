package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.OpeningHoursProperties;
import com.stefankabasaj.courtly.dto.response.CourtAvailabilityResponse;
import com.stefankabasaj.courtly.dto.response.CourtAvailabilityResponse.Slot;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.BookingRepository;
import com.stefankabasaj.courtly.repository.CourtRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CourtAvailabilityService")
class CourtAvailabilityServiceTest {

    @Mock CourtRepository   courtRepository;
    @Mock BookingRepository bookingRepository;

    private CourtAvailabilityService service;
    private Court court;
    private final LocalDate future = LocalDate.now().plusDays(10);

    @BeforeEach
    void setUp() {
        service = new CourtAvailabilityService(
                courtRepository, bookingRepository, new OpeningHoursProperties(8, 12));
        court = Court.builder().id(UUID.randomUUID()).name("Court A")
                .surface(Court.Surface.CLAY).pricePerHour(new BigDecimal("25.00")).active(true).build();
    }

    private Booking booking(int fromHour, int toHour) {
        return Booking.builder().id(UUID.randomUUID()).court(court)
                .startTime(future.atTime(fromHour, 0)).endTime(future.atTime(toHour, 0))
                .status(Booking.BookingStatus.PENDING).build();
    }

    private List<Boolean> availability(CourtAvailabilityResponse resp) {
        return resp.slots().stream().map(Slot::available).toList();
    }

    @Test
    @DisplayName("one slot per opening hour; booked hours are unavailable, edges are free")
    void marksBookedHours() {
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
        when(bookingRepository.findActiveOverlapping(eq(court.getId()),
                eq(future.atTime(8, 0)), eq(future.atTime(12, 0)), eq(Booking.BookingStatus.CANCELLED)))
                .thenReturn(List.of(booking(9, 11)));

        CourtAvailabilityResponse resp = service.getAvailability(court.getId(), future);

        assertThat(resp.slots()).hasSize(4);
        assertThat(resp.slots().get(0).startTime()).isEqualTo(future.atTime(8, 0));
        assertThat(resp.slots().get(3).endTime()).isEqualTo(future.atTime(12, 0));
        // 8-9 free, 9-10 and 10-11 booked, 11-12 free (back-to-back with the booking)
        assertThat(availability(resp)).containsExactly(true, false, false, true);
    }

    @Test
    @DisplayName("a past day has no available slots")
    void pastDay() {
        LocalDate past = LocalDate.now().minusDays(1);
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));
        when(bookingRepository.findActiveOverlapping(any(), any(), any(), any())).thenReturn(List.of());

        assertThat(availability(service.getAvailability(court.getId(), past)))
                .containsOnly(false);
    }

    @Test
    @DisplayName("unknown court → 404")
    void unknownCourt() {
        UUID id = UUID.randomUUID();
        when(courtRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailability(id, future))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("inactive court → BusinessException, no booking lookup")
    void inactiveCourt() {
        court.setActive(false);
        when(courtRepository.findById(court.getId())).thenReturn(Optional.of(court));

        assertThatThrownBy(() -> service.getAvailability(court.getId(), future))
                .isInstanceOf(BusinessException.class);
        verify(bookingRepository, never()).findActiveOverlapping(any(), any(), any(), any());
    }
}
