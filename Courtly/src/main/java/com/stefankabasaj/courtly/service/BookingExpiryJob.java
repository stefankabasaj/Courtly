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
 * Releases unpaid bookings after their payment deadline.
 * SKIP LOCKED keeps concurrent jobs from processing the same booking. Entity
 * updates preserve optimistic locking, timestamps and transactional outbox events.
 * Email delivery remains at least once; this job does not send emails directly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingExpiryJob {

    static final String EXPIRED_REASON = "Not paid in time";
    static final int BATCH_SIZE = 100;

    private final BookingRepository bookingRepository;
    private final BookingProperties bookingProperties;
    private final BookingEventRecorder events;

    @Scheduled(fixedDelayString = "${app.booking.expiry-check-interval-ms:60000}")
    @Transactional
    public void cancelUnpaidBookings() {
        LocalDateTime cutoff = LocalDateTime.now().minus(bookingProperties.paymentTimeout());

        List<UUID> bookingIds = bookingRepository.lockExpiredPendingIds(cutoff, BATCH_SIZE);
        if (bookingIds.isEmpty()) {
            return;
        }

        for (Booking booking : bookingRepository.findAllByIdWithDetails(bookingIds)) {
            booking.setStatus(Booking.BookingStatus.CANCELLED);
            booking.setCancellationReason(EXPIRED_REASON);
            events.record(booking, BookingEventType.BOOKING_EXPIRED);
        }
        log.info("Cancelled {} unpaid booking(s) older than {}", bookingIds.size(), bookingProperties.paymentTimeout());
    }
}
