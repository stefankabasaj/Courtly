package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminCancelRequest(
        @NotBlank(message = "Admin cancellation reason is required")
        @Size(max = 500, message = "Reason must be 500 characters or less")
        String reason
) {}
