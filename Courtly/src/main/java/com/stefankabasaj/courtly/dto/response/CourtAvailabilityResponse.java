package com.stefankabasaj.courtly.dto.response;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

// one court, one day, one entry per bookable hour
public record CourtAvailabilityResponse(
        UUID courtId,
        String courtName,
        LocalDate date,
        List<Slot> slots
) {
    // available = not in the past and not overlapping a PENDING or CONFIRMED booking
    public record Slot(LocalDateTime startTime, LocalDateTime endTime, boolean available) {}
}
