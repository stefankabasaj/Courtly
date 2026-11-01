package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// scheduling is for BookingExpiryJob
@Configuration
@EnableScheduling
@EnableConfigurationProperties({
        AdminProperties.class,
        BookingProperties.class,
        OpeningHoursProperties.class,
        LoginRateLimitProperties.class
})
public class AppConfig {}
