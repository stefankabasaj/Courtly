package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;
import java.util.UUID;

public record BookingRequest(
        @NotNull(message = "Court ID is required")
        UUID courtId,

        @NotNull(message = "Start time is required")
        @Future(message = "Start time must be in the future")
        LocalDateTime startTime,

        @NotNull(message = "End time is required")
        LocalDateTime endTime
) {}
