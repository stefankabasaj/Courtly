package com.stefankabasaj.courtly.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * What goes to Kafka, as JSON. This is a contract with other services (the
 * notification service has its own copy), so add fields freely but don't
 * rename or remove them - old consumers would break.
 *
 * Everything a consumer needs is in here, so it never has to call back into
 * this API to send an email.
 */
public record BookingEvent(
        UUID eventId,
        BookingEventType type,
        LocalDateTime occurredAt,
        UUID bookingId,
        String userEmail,
        String courtName,
        LocalDateTime startTime,
        LocalDateTime endTime,
        BigDecimal totalPrice,
        String reason,          // CANCELLED and EXPIRED only
        LocalDateTime payBy,    // CREATED only
        String requestId        // the API request that caused it, for following it through the logs. null for jobs
) {}
