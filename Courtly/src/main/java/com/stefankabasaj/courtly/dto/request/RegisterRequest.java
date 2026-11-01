package com.stefankabasaj.courtly.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be 255 characters or less")
        String email,

        @NotBlank(message = "Password is required")
        // BCrypt only uses the first 72 bytes, so a longer password gives a false sense of security
        @Size(min = 8, max = 72, message = "Password must be between 8 and 72 characters")
        String password
) {}
