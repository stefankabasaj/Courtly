package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.Size;

public record CancelBookingRequest(
        @Size(max = 500, message = "Reason must be 500 characters or less")
        String reason   // optional
) {}
