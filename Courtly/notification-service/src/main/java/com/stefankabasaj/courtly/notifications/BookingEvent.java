package com.stefankabasaj.courtly.notifications;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Our copy of the API's BookingEvent. Deliberately not a shared library - the
 * JSON is the contract, and each service can deploy on its own as long as the
 * fields keep their names. Unknown fields are ignored (Spring Boot's default),
 * so the API can add new ones without breaking us.
 *
 * type is a String, not an enum: an event type we don't know yet is something
 * to skip, not a message to fail on.
 */
public record BookingEvent(
        UUID eventId,
        String type,
        LocalDateTime occurredAt,
        UUID bookingId,
        String userEmail,
        String courtName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal totalPrice,
        String reason,
        LocalDateTime payBy,
        String requestId    // the API request behind it; null for the API's own jobs
) {}
