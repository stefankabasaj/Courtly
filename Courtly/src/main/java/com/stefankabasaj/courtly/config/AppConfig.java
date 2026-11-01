package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// scheduling is for BookingExpiryJob and LoginAttemptService's cleanup
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        AdminProperties.class,
        BookingProperties.class,
        LoginRateLimitProperties.class
})
public class AppConfig {}
