package com.stefankabasaj.courtly.dto.request;

import com.stefankabasaj.courtly.validation.MaxBytes;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank(message = "Email is required")
        @Email(message = "Must be a valid email address")
        @Size(max = 255, message = "Email must be 255 characters or less")
        String email,

        @NotBlank(message = "Password is required")
        @Size(min = 8, message = "Password must be at least 8 characters")
        // BCrypt's limit. bytes, not characters - accented letters and emoji count double or more
        @MaxBytes(value = 72, message = "Password is too long (72 bytes max - accented letters and emoji take 2-4 each)")
        String password
) {}
