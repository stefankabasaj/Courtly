package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PaymentRequest(
        @NotBlank(message = "Payment method is required")
        @Size(max = 30, message = "Payment method must be 30 characters or less")
        String paymentMethod
) {}
