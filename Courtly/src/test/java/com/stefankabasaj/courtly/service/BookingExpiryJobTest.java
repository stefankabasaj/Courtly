package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.repository.BookingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BookingExpiryJob")
class BookingExpiryJobTest {

    @Mock BookingRepository bookingRepository;

    @Test
    @DisplayName("cancels PENDING bookings created before now minus the timeout")
    void cancelsWithCutoff() {
        var job = new BookingExpiryJob(bookingRepository, new BookingProperties(Duration.ofMinutes(15)));
        when(bookingRepository.cancelPendingCreatedBefore(any(), any(), any(), any(), any())).thenReturn(2);

        LocalDateTime before = LocalDateTime.now();
        job.cancelUnpaidBookings();
        LocalDateTime after = LocalDateTime.now();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bookingRepository).cancelPendingCreatedBefore(
                cutoff.capture(), any(), eq(BookingExpiryJob.EXPIRED_REASON),
                eq(Booking.BookingStatus.PENDING), eq(Booking.BookingStatus.CANCELLED));

        assertThat(cutoff.getValue())
                .isAfterOrEqualTo(before.minusMinutes(15))
                .isBeforeOrEqualTo(after.minusMinutes(15));
    }
}
