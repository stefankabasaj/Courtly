package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// app.security.login.* - how many failed logins are allowed before we answer 429
@ConfigurationProperties(prefix = "app.security.login")
public record LoginRateLimitProperties(
        @DefaultValue("5")   int maxFailuresPerEmail,
        @DefaultValue("20")  int maxFailuresPerIp,
        @DefaultValue("15m") Duration window
) {}
