package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.NotBlank;

public record PaymentRequest(
        @NotBlank(message = "Payment method is required")
        String paymentMethod
) {}
