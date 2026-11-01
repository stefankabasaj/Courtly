package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.event.BookingEventType;
import com.stefankabasaj.courtly.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingExpiryJob")
class BookingExpiryJobTest {

    @Mock BookingRepository    bookingRepository;
    @Mock BookingEventRecorder events;

    private BookingExpiryJob job() {
        return new BookingExpiryJob(bookingRepository, new BookingProperties(Duration.ofMinutes(15)), events);
    }

    @Test
    @DisplayName("looks for PENDING bookings created before now minus the timeout")
    void cutoff() {
        when(bookingRepository.lockExpiredPendingIds(any(), anyInt())).thenReturn(List.of());

        LocalDateTime before = LocalDateTime.now();
        job().cancelUnpaidBookings();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bookingRepository).lockExpiredPendingIds(cutoff.capture(), eq(BookingExpiryJob.BATCH_SIZE));
        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minusMinutes(15))
                .isBeforeOrEqualTo(after.minusMinutes(15));
    }

    @Test
    @DisplayName("cancels each expired booking and records BOOKING_EXPIRED for it")
    void cancelsAndRecords() {
        Booking a = Booking.builder().id(UUID.randomUUID()).status(Booking.BookingStatus.PENDING).build();
        Booking b = Booking.builder().id(UUID.randomUUID()).status(Booking.BookingStatus.PENDING).build();
        when(bookingRepository.lockExpiredPendingIds(any(), anyInt())).thenReturn(List.of(a.getId(), b.getId()));
        when(bookingRepository.findAllByIdWithDetails(List.of(a.getId(), b.getId()))).thenReturn(List.of(a, b));

        job().cancelUnpaidBookings();

        for (Booking booking : List.of(a, b)) {
            assertThat(booking.getStatus()).isEqualTo(Booking.BookingStatus.CANCELLED);
            assertThat(booking.getCancellationReason()).isEqualTo(BookingExpiryJob.EXPIRED_REASON);
            verify(events).record(booking, BookingEventType.BOOKING_EXPIRED);
        }
    }

    @Test
    @DisplayName("nothing expired - no further queries, no events")
    void nothingToDo() {
        when(bookingRepository.lockExpiredPendingIds(any(), anyInt())).thenReturn(List.of());

        job().cancelUnpaidBookings();

        verify(bookingRepository, never()).findAllByIdWithDetails(any());
        verifyNoInteractions(events);
    }
}
