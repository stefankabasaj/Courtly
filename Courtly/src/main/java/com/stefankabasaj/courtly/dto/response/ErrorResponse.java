package com.stefankabasaj.courtly.dto.response;

import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
        int status,
        String error,
        String message,
        String path,
        LocalDateTime timestamp,
        Map<String, String> fieldErrors
) {
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, LocalDateTime.now(), null);
    }

    public static ErrorResponse withFields(int status, String error, String message,
                                           String path, Map<String, String> fieldErrors) {
        return new ErrorResponse(status, error, message, path, LocalDateTime.now(), fieldErrors);
    }
}
