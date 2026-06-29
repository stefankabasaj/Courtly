package com.stefankabasaj.courtly.controller;

import com.stefankabasaj.courtly.dto.request.AdminCancelRequest;
import com.stefankabasaj.courtly.dto.request.BookingRequest;
import com.stefankabasaj.courtly.dto.request.CancelBookingRequest;
import com.stefankabasaj.courtly.dto.response.BookingResponse;
import com.stefankabasaj.courtly.dto.response.PageResponse;
import com.stefankabasaj.courtly.security.AuthenticatedUser;
import com.stefankabasaj.courtly.service.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
@Validated
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<BookingResponse> create(
            @Valid @RequestBody BookingRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(bookingService.createBooking(principal.userId(), request));
    }

    @GetMapping("/my")
    public ResponseEntity<List<BookingResponse>> getMyBookings(
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(bookingService.getMyBookings(principal.userId()));
    }

    // pass the caller down so the service can do the ownership check
    @GetMapping("/{id}")
    public ResponseEntity<BookingResponse> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(bookingService.getBookingById(id, principal));
    }

    @PatchMapping("/{id}/cancel")
    public ResponseEntity<BookingResponse> cancel(
            @PathVariable UUID id,
            @RequestBody(required = false) CancelBookingRequest request,
            @AuthenticationPrincipal AuthenticatedUser principal) {
        return ResponseEntity.ok(
                bookingService.cancelBooking(id, principal.userId(), request));
    }

    @DeleteMapping("/{id}/admin-cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BookingResponse> adminCancel(
            @PathVariable UUID id,
            @Valid @RequestBody AdminCancelRequest request) {
        return ResponseEntity.ok(bookingService.adminCancelBooking(id, request));
    }

    // admin-only. size is capped at 100 so nobody pulls the whole table at once
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PageResponse<BookingResponse>> getAll(
            @RequestParam(defaultValue = "0")  @Min(0)           int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(bookingService.getAllBookings(page, size));
    }
}
