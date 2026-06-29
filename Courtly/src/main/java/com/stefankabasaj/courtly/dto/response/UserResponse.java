package com.stefankabasaj.courtly.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String role,
        boolean active,
        LocalDateTime createdAt
) {}
