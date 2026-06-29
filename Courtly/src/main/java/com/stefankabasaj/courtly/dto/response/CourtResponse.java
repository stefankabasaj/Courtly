package com.stefankabasaj.courtly.dto.response;

import java.math.BigDecimal;
import java.util.UUID;

public record CourtResponse(
        UUID id,
        String name,
        String surface,
        BigDecimal pricePerHour,
        boolean active
) {}
