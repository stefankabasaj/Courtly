package com.stefankabasaj.courtly.dto.request;

public record CancelBookingRequest(
        String reason   // optional
) {}
