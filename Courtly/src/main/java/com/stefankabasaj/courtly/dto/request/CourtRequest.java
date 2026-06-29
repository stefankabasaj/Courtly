package com.stefankabasaj.courtly.dto.request;

import com.stefankabasaj.courtly.entity.Court;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record CourtRequest(
        @NotBlank(message = "Court name is required")
        @Size(max = 100, message = "Name must be 100 characters or less")
        String name,

        @NotNull(message = "Surface type is required")
        Court.Surface surface,

        @NotNull(message = "Price per hour is required")
        @DecimalMin(value = "0.01", message = "Price must be greater than zero")
        BigDecimal pricePerHour
) {}
