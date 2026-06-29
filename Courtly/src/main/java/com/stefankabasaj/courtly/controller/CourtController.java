package com.stefankabasaj.courtly.controller;

import com.stefankabasaj.courtly.dto.request.CourtRequest;
import com.stefankabasaj.courtly.dto.response.CourtResponse;
import com.stefankabasaj.courtly.service.CourtService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/courts")
@RequiredArgsConstructor
public class CourtController {

    private final CourtService courtService;

    @GetMapping
    public ResponseEntity<List<CourtResponse>> getAll() {
        return ResponseEntity.ok(courtService.getAllActiveCourts());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CourtResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(courtService.getCourtById(id));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CourtResponse> create(@Valid @RequestBody CourtRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(courtService.createCourt(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CourtResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody CourtRequest request) {
        return ResponseEntity.ok(courtService.updateCourt(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        courtService.deactivateCourt(id);
        return ResponseEntity.noContent().build();
    }
}
