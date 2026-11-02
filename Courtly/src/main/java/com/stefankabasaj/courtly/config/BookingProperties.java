package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.time.LocalDateTime;

// app.booking.* - how long a PENDING booking holds its slot before it has to be paid
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(@DefaultValue("15m") Duration paymentTimeout) {

    // the one place the deadline is worked out - the API response, the email
    // and the payment check all have to agree on it
    public LocalDateTime payBy(LocalDateTime createdAt) {
        return createdAt.plus(paymentTimeout);
    }
}
