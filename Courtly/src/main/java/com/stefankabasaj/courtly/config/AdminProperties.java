package com.stefankabasaj.courtly.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

// app.admin.* - the admin account created at startup (ADMIN_EMAIL / ADMIN_PASSWORD)
@ConfigurationProperties(prefix = "app.admin")
public record AdminProperties(String email, String password) {}
