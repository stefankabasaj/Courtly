package com.stefankabasaj.courtly.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stefankabasaj.courtly.security.ApiSecurityErrorHandler;
import com.stefankabasaj.courtly.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
// registered here rather than in AppConfig, so tests that @Import this class get it too
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final CorsProperties          corsProperties;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        // JSON bodies for 401/403 raised inside the filter chain
        var errorHandler = new ApiSecurityErrorHandler(objectMapper);

        return http
                // JWT lives in the Authorization header, not a cookie, so CSRF doesn't apply
                .csrf(AbstractHttpConfigurer::disable)

                // off unless CORS_ALLOWED_ORIGINS is set. behind one domain (nginx, or the
                // frontend proxying /api) the browser never makes a cross-origin call
                .cors(c -> {
                    if (corsProperties.enabled()) {
                        c.configurationSource(corsSource());
                    } else {
                        c.disable();
                    }
                })

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
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/courts/**").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/courts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT,  "/api/v1/courts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/v1/courts/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/courts/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private CorsConfigurationSource corsSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(corsProperties.allowedOrigins());
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // so the frontend can read Retry-After on a 429
        config.setExposedHeaders(List.of("Retry-After"));
        // the token goes in a header, never a cookie, so no credentials mode
        config.setAllowCredentials(false);
        config.setMaxAge(Duration.ofHours(1));

        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // strength 12 ~= 250ms per hash. slow enough to hurt brute force, fast enough nobody notices at login
        return new BCryptPasswordEncoder(12);
    }
}
