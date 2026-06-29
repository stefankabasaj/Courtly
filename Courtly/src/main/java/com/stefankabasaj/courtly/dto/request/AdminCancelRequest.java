package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AdminCancelRequest(
        @NotBlank(message = "Admin cancellation reason is required")
        String reason
) {}
