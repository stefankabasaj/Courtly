package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Cancels PENDING bookings that weren't paid in time. Without this an unpaid
 * booking holds its slot forever, so anyone could block a whole day for free.
 *
 * A single UPDATE, so it's safe to run on several instances at once. It bumps
 * the @Version, so a payment that races with it fails with 409 instead of
 * confirming a booking that was just cancelled.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryJob {

    static final String EXPIRED_REASON = "Not paid in time";

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;

    @Scheduled(fixedDelayString = "${app.booking.expiry-check-interval-ms:60000}")
    @Transactional
    public void cancelUnpaidBookings() {
        LocalDateTime now    = LocalDateTime.now();
        LocalDateTime cutoff = now.minus(bookingProperties.paymentTimeout());

        int cancelled = bookingRepository.cancelPendingCreatedBefore(
                cutoff, now, EXPIRED_REASON,
                Booking.BookingStatus.PENDING, Booking.BookingStatus.CANCELLED);

        if (cancelled > 0) {
            log.info("Cancelled {} unpaid booking(s) older than {}", cancelled, bookingProperties.paymentTimeout());
        }
    }
}
