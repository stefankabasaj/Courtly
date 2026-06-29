package com.stefankabasaj.courtly.service;

import com.stefankabasaj.courtly.dto.request.CourtRequest;
import com.stefankabasaj.courtly.dto.response.CourtResponse;
import com.stefankabasaj.courtly.entity.Court;
import com.stefankabasaj.courtly.exception.BusinessException;
import com.stefankabasaj.courtly.exception.ConflictException;
import com.stefankabasaj.courtly.exception.ResourceNotFoundException;
import com.stefankabasaj.courtly.repository.CourtRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CourtService {

    private final CourtRepository courtRepository;

    @Transactional(readOnly = true)
    public List<CourtResponse> getAllActiveCourts() {
        return courtRepository.findAllByActiveTrue()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public CourtResponse getCourtById(UUID id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public CourtResponse createCourt(CourtRequest request) {
        // trim before the check or "Court A" and "Court A " look like different names
        String name = request.name().trim();
        log.info("Creating court name='{}'", name);

        if (courtRepository.existsByName(name)) {
            throw new ConflictException("A court named '" + name + "' already exists.");
        }

        Court court = Court.builder()
                .name(name)
                .surface(request.surface())
                .pricePerHour(request.pricePerHour())
                .build();

        Court saved = courtRepository.save(court);
        log.info("Court created id={} name='{}'", saved.getId(), saved.getName());
        return toResponse(saved);
    }

    @Transactional
    public CourtResponse updateCourt(UUID id, CourtRequest request) {
        String name = request.name().trim();
        log.info("Updating court id={}", id);

        Court court = findOrThrow(id);

        // only worry about name clashes if the name actually changed
        if (!court.getName().equalsIgnoreCase(name) &&
                courtRepository.existsByNameAndIdNot(name, id)) {
            throw new ConflictException("A court named '" + name + "' already exists.");
        }

        court.setName(name);
        court.setSurface(request.surface());
        court.setPricePerHour(request.pricePerHour());

        return toResponse(courtRepository.save(court));
    }

    @Transactional
    public void deactivateCourt(UUID id) {
        log.info("Deactivating court id={}", id);
        Court court = findOrThrow(id);

        if (!court.isActive()) {
            throw new BusinessException("Court '" + court.getName() + "' is already deactivated.");
        }

        court.setActive(false);
        courtRepository.save(court);
    }

    private Court findOrThrow(UUID id) {
        return courtRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Court", id));
    }

    CourtResponse toResponse(Court court) {
        return new CourtResponse(
                court.getId(),
                court.getName(),
                court.getSurface().name(),
                court.getPricePerHour(),
                court.isActive()
        );
    }
}
