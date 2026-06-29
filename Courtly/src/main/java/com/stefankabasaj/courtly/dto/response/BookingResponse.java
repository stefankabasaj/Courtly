package com.stefankabasaj.courtly.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record BookingResponse(
        UUID id,
        UUID userId,
        String userEmail,
        UUID courtId,
        String courtName,
        String courtSurface,
        LocalDateTime startTime,
        LocalDateTime endTime,
        long durationHours,
        BigDecimal pricePerHour,
        BigDecimal totalPrice,
        String status,
        String cancellationReason,
        boolean cancellable,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
