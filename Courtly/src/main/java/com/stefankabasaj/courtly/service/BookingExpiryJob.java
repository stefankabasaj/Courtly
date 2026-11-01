package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.event.BookingEventRecorder;
import com.stefankabasaj.courtly.event.BookingEventType;
import com.stefankabasaj.courtly.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Cancels PENDING bookings that weren't paid in time. Without this an unpaid
 * booking holds its slot forever, so anyone could block a whole day for free.
 *
 * The expired rows are locked with SKIP LOCKED, so when both instances run
 * this at once each booking is expired - and its email sent - exactly once.
 * The row lock also makes a payment for the same booking wait, and its
 * @Version check then fails with 409 instead of confirming a cancelled booking.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryJob {

    static final String EXPIRED_REASON = "Not paid in time";
    static final int    BATCH_SIZE     = 100;

    private final BookingRepository    bookingRepository;
    private final BookingProperties    bookingProperties;
    private final BookingEventRecorder events;

    @Scheduled(fixedDelayString = "${app.booking.expiry-check-interval-ms:60000}")
    @Transactional
    public void cancelUnpaidBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minus(bookingProperties.paymentTimeout());

        List<UUID> ids = bookingRepository.lockExpiredPendingIds(cutoff, BATCH_SIZE);
        if (ids.isEmpty()) {
            return;
        }

        // plain entity updates, so Hibernate bumps version and updatedAt as usual
        for (Booking booking : bookingRepository.findAllByIdWithDetails(ids)) {
            booking.setStatus(Booking.BookingStatus.CANCELLED);
            booking.setCancellationReason(EXPIRED_REASON);
            events.record(booking, BookingEventType.BOOKING_EXPIRED);
        }
        log.info("Cancelled {} unpaid booking(s) older than {}", ids.size(), bookingProperties.paymentTimeout());
    }
}
