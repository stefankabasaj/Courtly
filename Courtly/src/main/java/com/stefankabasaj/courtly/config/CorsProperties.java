package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// app.cors.allowed-origins - comma-separated, e.g. http://localhost:3000. empty = CORS off
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of()
                : allowedOrigins.stream().map(String::trim).filter(o -> !o.isEmpty()).toList();
    }

    public boolean enabled() {
        return !allowedOrigins.isEmpty();
    }
}
