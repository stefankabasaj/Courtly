package com.stefankabasaj.courtly.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.config.BookingProperties;
import com.stefankabasaj.courtly.config.RequestIdFilter;
import com.stefankabasaj.courtly.entity.Booking;
import com.stefankabasaj.courtly.entity.OutboxEvent;
import com.stefankabasaj.courtly.repository.OutboxEventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;

/**
 * Writes a booking event to the outbox table. Never sends anything itself.
 *
 * MANDATORY: it must run inside the transaction that changes the booking. If
 * that rolls back, the event goes with it; if it commits, the event is there.
 * Sending to Kafka directly from the service would break exactly that - a
 * rollback after the send, or a crash before it, and the email no longer
 * matches what happened.
 */
@Component
@RequiredArgsConstructor
public class BookingEventRecorder {

    private final OutboxEventRepository outbox;
    private final ObjectMapper          objectMapper;
    private final BookingProperties     bookingProperties;
    private final MeterRegistry         metrics;

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(Booking booking, BookingEventType type) {
        UUID          eventId = UUID.randomUUID();
        LocalDateTime now     = LocalDateTime.now();

        BookingEvent event = new BookingEvent(
                eventId,
                type,
                now,
                booking.getId(),
                booking.getUser().getEmail(),
                booking.getCourt().getName(),
                booking.getStartTime(),
                booking.getEndTime(),
                booking.getTotalPrice(),
                type == BookingEventType.BOOKING_CANCELLED || type == BookingEventType.BOOKING_EXPIRED
                        ? booking.getCancellationReason() : null,
                type == BookingEventType.BOOKING_CREATED && booking.getCreatedAt() != null
                        ? bookingProperties.payBy(booking.getCreatedAt()) : null,
                MDC.get(RequestIdFilter.MDC_KEY)
        );

        outbox.save(OutboxEvent.builder()
                .id(eventId)
                .aggregateId(booking.getId())
                .eventType(type.name())
                .payload(toJson(event))
                .createdAt(now)
                .build());

        // every booking state change goes through here, so this one counter covers them all:
        // courtly_bookings_total{event="created|confirmed|cancelled|expired"}
        metrics.counter("courtly.bookings", "event",
                type.name().substring("BOOKING_".length()).toLowerCase(Locale.ROOT)).increment();
    }

    private String toJson(BookingEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // a plain record of strings, numbers and dates - this can't really happen
            throw new IllegalStateException("Could not serialize " + event.type(), e);
        }
    }
}
