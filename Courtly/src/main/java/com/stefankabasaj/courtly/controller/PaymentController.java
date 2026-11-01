package com.stefankabasaj.courtly.controller;

import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import com.stefankabasaj.courtly.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // ownership check happens in the service
    @PostMapping("/{bookingId}")
    public ResponseEntity<PaymentResponse> processPayment(
            @PathVariable UUID bookingId,
            @Valid @RequestBody PaymentRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(
                paymentService.processPayment(bookingId, principal.userId(), request));
    }

    // only the booking's owner or an admin - checked in the service
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<PaymentResponse> getByBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(paymentService.getPaymentByBookingId(bookingId, principal));
    }
}
