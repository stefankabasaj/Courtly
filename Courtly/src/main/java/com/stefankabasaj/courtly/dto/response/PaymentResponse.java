package com.stefankabasaj.courtly.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID bookingId,
        BigDecimal amount,
        String status,
        String paymentMethod,
        String failureReason,
        LocalDateTime createdAt,
        LocalDateTime processedAt
) {}
