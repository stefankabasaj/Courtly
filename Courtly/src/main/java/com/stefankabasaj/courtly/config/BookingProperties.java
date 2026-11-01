package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

// app.booking.* - how long a PENDING booking holds its slot before it has to be paid
@ConfigurationProperties(prefix = "app.booking")
public record BookingProperties(@DefaultValue("15m") Duration paymentTimeout) {}
