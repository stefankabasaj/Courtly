package com.stefankabasaj.courtly.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.security.ApiSecurityErrorHandler;
import com.stefankabasaj.courtly.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        // JSON bodies for 401/403 raised inside the filter chain
        var errorHandler = new ApiSecurityErrorHandler(objectMapper);

        return http
                // JWT lives in the Authorization header, not a cookie, so CSRF doesn't apply
                .csrf(AbstractHttpConfigurer::disable)

                // fully stateless - no HttpSession, so any instance can serve any request
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                // a few sensible security headers
                .headers(h -> h
                        .frameOptions(fo -> fo.deny())
                        .contentTypeOptions(cto -> {})
                        .referrerPolicy(rp -> rp.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                )

                .exceptionHandling(e -> e
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler)
                )

                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courts/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,  "/api/v1/courts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/courts/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength 12 ~= 250ms per hash. slow enough to hurt brute force, fast enough nobody notices at login
        return new BCryptPasswordEncoder(12);
    }
}
