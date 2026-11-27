package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// app.security.register.* - how many accounts one IP may create per window
@ConfigurationProperties(prefix = "app.security.register")
public record RegistrationRateLimitProperties(
        @DefaultValue("10") int maxPerIp,
        @DefaultValue("1h") Duration window
) {}
