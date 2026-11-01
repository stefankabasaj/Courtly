package com.stefankabasaj.courtly.controller;

import com.stefankabasaj.courtly.dto.request.LoginRequest;
import com.stefankabasaj.courtly.dto.request.RegisterRequest;
import com.stefankabasaj.courtly.dto.response.AuthResponse;
import com.stefankabasaj.courtly.dto.response.UserResponse;
import com.stefankabasaj.courtly.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(authService.register(request));
    }

    // remote address, not X-Forwarded-For: that header is set by the client and
    // could be used to dodge the per-IP login limit. Behind a reverse proxy, set
    // server.forward-headers-strategy=native so this becomes the real client IP
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ResponseEntity.ok(authService.login(request, httpRequest.getRemoteAddr()));
    }
}
