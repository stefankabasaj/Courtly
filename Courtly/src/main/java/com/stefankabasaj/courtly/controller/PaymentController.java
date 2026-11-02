package com.stefankabasaj.courtly.controller;

import com.stefankabasaj.courtly.dto.request.PaymentRequest;
import com.stefankabasaj.courtly.dto.response.PaymentResponse;
import com.stefankabasaj.courtly.idempotency.IdempotencyService;
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

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED        = "Idempotent-Replayed";

    private final PaymentService     paymentService;
    private final IdempotencyService idempotency;

    // ownership check happens in the service.
    // with an Idempotency-Key, a repeat of the same request gets the first answer
    // back (Idempotent-Replayed: true) instead of a second payment attempt
    @PostMapping("/{bookingId}")
    public ResponseEntity<PaymentResponse> processPayment(
            @PathVariable UUID bookingId,
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        if (idempotencyKey == null) {
            return ResponseEntity.ok(paymentService.processPayment(bookingId, principal.userId(), request));
        }

        var outcome = idempotency.execute(
                "payment:" + principal.userId(),
                idempotencyKey,
                bookingId + ":" + request.paymentMethod(),
                PaymentResponse.class,
                () -> paymentService.processPayment(bookingId, principal.userId(), request));

        return ResponseEntity.ok()
                .header(REPLAYED, String.valueOf(outcome.replayed()))
                .body(outcome.result());
    }

    // only the booking's owner or an admin - checked in the service
    @GetMapping("/booking/{bookingId}")
    public ResponseEntity<PaymentResponse> getByBooking(
            @PathVariable UUID bookingId,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(paymentService.getPaymentByBookingId(bookingId, principal));
    }
}
