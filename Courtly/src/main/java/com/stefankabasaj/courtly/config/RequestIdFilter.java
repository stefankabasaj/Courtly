package com.stefankabasaj.courtly.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id and puts it in every log line it causes - and,
 * through the booking event, in the notification service's logs too. nginx
 * sets X-Request-Id; called directly, we make one up. The client gets it back
 * in the response, so "my booking failed" can be matched to the exact logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER  = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    // anything else (too long, odd characters) could be used to mess with the logs
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String incoming  = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
