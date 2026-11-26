package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * app.security.login.* - how many failed logins are allowed before we answer 429.
 *
 * maxFailuresPerEmail counts one email from one IP, so a stranger can't lock
 * someone out of their account from their own machine. maxFailuresPerEmailAllIps
 * is the ceiling for one email across every IP - against guessing spread over
 * many addresses.
 */
@ConfigurationProperties(prefix = "app.security.login")
public record LoginRateLimitProperties(
        @DefaultValue("5")   int maxFailuresPerEmail,
        @DefaultValue("20")  int maxFailuresPerIp,
        @DefaultValue("20")  int maxFailuresPerEmailAllIps,
        @DefaultValue("15m") Duration window
) {}
